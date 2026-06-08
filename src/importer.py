import datetime

# ruff: noqa: C901
import json
import sqlite3
import sys
import uuid

import xxhash
from sqlalchemy import create_engine, func
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.orm import sessionmaker
from tqdm import tqdm

from src.models import Base, Game, GameEvent, Player, Position, Turn

# Use the synchronous psycopg2 driver for the ETL
PG_URL = "postgresql://dicechess_user:dicechess_password@localhost:5432/dicechess_analytics"
SQLITE_PATH = "dicechess.db"

engine = create_async_engine = create_engine(PG_URL, echo=False)
SessionLocal = sessionmaker(bind=engine)


def normalize_fen(fen_str: str) -> str:
    """Extracts piece placement, color, castling, and en_passant for deduplication."""
    parts = fen_str.split()
    return " ".join(parts[0:4])


def get_fen_hash(normalized_fen: str) -> int:
    """Generates an xxhash64 integer digest."""
    # xxhash generates unsigned 64-bit by default, postgres BigInteger is signed 64-bit.
    # Convert to signed 64-bit int.
    digest = xxhash.xxh64(normalized_fen).intdigest()
    if digest >= 2**63:
        digest -= 2**64
    return digest


def are_dice_equal(dices1, dices2):
    dices1 = dices1 or []
    dices2 = dices2 or []
    if len(dices1) != len(dices2):
        return False
    for d1, d2 in zip(dices1, dices2, strict=True):
        if (
            d1.get("value") != d2.get("value")
            or d1.get("allowed") != d2.get("allowed")
            or d1.get("used") != d2.get("used")
        ):
            return False
    return True


def main():
    limit_games = None
    if len(sys.argv) > 1 and sys.argv[1] == "--limit":
        try:
            limit_games = int(sys.argv[2])
        except (IndexError, ValueError):
            print("Usage: python src/importer.py [--limit N]")
            sys.exit(1)

    print("Connecting to PostgreSQL...")
    Base.metadata.create_all(engine)

    print("Connecting to SQLite...")
    sqlite_conn = sqlite3.connect(SQLITE_PATH)
    sqlite_conn.row_factory = sqlite3.Row
    cursor = sqlite_conn.cursor()

    cursor.execute("SELECT COUNT(*) FROM games")
    total_games = cursor.fetchone()[0]

    print(f"Total games in SQLite: {total_games}")

    cursor.execute("SELECT * FROM games")

    pg_session = SessionLocal()

    # Local caches to reduce DB lookups
    positions_cache = {}  # hash -> id
    players_cache = set()  # external_id

    batch_size = 250
    games_batch = []
    positions_batch = []
    players_batch = []
    turns_batch = []
    events_batch = []

    def execute_chunked_insert(session, model, batch, chunk_size=5000, on_conflict_index=None):
        for i in range(0, len(batch), chunk_size):
            chunk = batch[i : i + chunk_size]
            stmt = insert(model).values(chunk)
            if on_conflict_index:
                stmt = stmt.on_conflict_do_nothing(index_elements=on_conflict_index)
            session.execute(stmt)

    def flush_batches():
        if not games_batch:
            return

        # 1. Insert Players
        if players_batch:
            execute_chunked_insert(
                pg_session, Player, players_batch, on_conflict_index=["external_id"]
            )
            players_batch.clear()

        # 2. Insert Positions
        if positions_batch:
            execute_chunked_insert(
                pg_session, Position, positions_batch, on_conflict_index=["normalized_fen"]
            )
            pg_session.commit()

            # Update cache with new IDs in chunks
            hashes_to_fetch = list({p["fen_hash"] for p in positions_batch})
            for i in range(0, len(hashes_to_fetch), 5000):
                chunk = hashes_to_fetch[i : i + 5000]
                new_positions = (
                    pg_session.query(Position.id, Position.fen_hash)
                    .filter(Position.fen_hash.in_(chunk))
                    .all()
                )
                for pid, phash in new_positions:
                    positions_cache[phash] = pid

            positions_batch.clear()

        # Now link Game, Turn, GameEvent to Position IDs
        for g in games_batch:
            if g.get("_initial_fen_hash"):
                g["initial_position_id"] = positions_cache[g["_initial_fen_hash"]]
                del g["_initial_fen_hash"]
            if g.get("_final_fen_hash"):
                g["final_position_id"] = positions_cache[g["_final_fen_hash"]]
                del g["_final_fen_hash"]

        for t in turns_batch:
            t["position_id"] = positions_cache[t["_position_hash"]]
            del t["_position_hash"]
            if t.get("_position_after_hash"):
                t["position_after_id"] = positions_cache[t["_position_after_hash"]]
                del t["_position_after_hash"]

        # 3. Insert Games
        if games_batch:
            for i in range(0, len(games_batch), 5000):
                chunk = games_batch[i : i + 5000]
                stmt = insert(Game).values(chunk)
                stmt = stmt.on_conflict_do_update(
                    index_elements=["id"],
                    set_={
                        "white_money_delta": func.coalesce(
                            Game.white_money_delta, stmt.excluded.white_money_delta
                        ),
                        "black_money_delta": func.coalesce(
                            Game.black_money_delta, stmt.excluded.black_money_delta
                        ),
                        "time_initial_sec": func.coalesce(
                            Game.time_initial_sec, stmt.excluded.time_initial_sec
                        ),
                        "time_increment_sec": func.coalesce(
                            Game.time_increment_sec, stmt.excluded.time_increment_sec
                        ),
                        "initial_stake_amount": func.coalesce(
                            Game.initial_stake_amount, stmt.excluded.initial_stake_amount
                        ),
                        "final_stake_amount": func.coalesce(
                            Game.final_stake_amount, stmt.excluded.final_stake_amount
                        ),
                        "stake_currency": func.coalesce(
                            Game.stake_currency, stmt.excluded.stake_currency
                        ),
                    },
                )
                pg_session.execute(stmt)
            games_batch.clear()

        # 4. Insert Turns
        if turns_batch:
            execute_chunked_insert(pg_session, Turn, turns_batch)
            turns_batch.clear()

        # 5. Insert Events
        if events_batch:
            execute_chunked_insert(pg_session, GameEvent, events_batch)
            events_batch.clear()

        pg_session.commit()

    processed_count = 0
    skipped_bots = 0
    seen_games = set()

    for row in tqdm(cursor, total=total_games, desc="Importing games"):
        game_id = row["game_id"]

        try:
            game_uuid = str(game_id).lower()
        except ValueError:
            continue

        if game_uuid in seen_games:
            continue
        seen_games.add(game_uuid)

        moves_json_str = row["moves_json"]

        if not moves_json_str:
            continue

        try:
            moves_data = json.loads(moves_json_str)
        except json.JSONDecodeError:
            continue

        # Skip bot games if they don't have gameMoveHistoryStateMap
        if "gameMoveHistoryStateMap" not in moves_data:
            skipped_bots += 1
            continue

        state_map = moves_data["gameMoveHistoryStateMap"]
        if not state_map:
            continue

        # Process players
        w_username = row["white_player_username"]
        b_username = row["black_player_username"]

        # Simple UUID generation for players based on username or external_id
        # Let's just use the external_id to identify them
        w_id = row["white_player_id"]
        b_id = row["black_player_id"]

        if w_id and w_id not in players_cache:
            players_batch.append(
                {
                    "external_id": str(w_id),
                    "username": w_username,
                    "player_type": "human",
                }
            )
            players_cache.add(w_id)

        if b_id and b_id not in players_cache:
            players_batch.append(
                {
                    "external_id": str(b_id),
                    "username": b_username,
                    "player_type": "human",
                }
            )
            players_cache.add(b_id)

        # We need a predictable UUID for games to link turns
        # SQLite game_id is usually a UUID string.
        try:
            game_uuid = str(game_id)
        except ValueError:
            continue

        # Process metadata
        meta_data = {}
        if row["metadata_json"]:
            try:
                parsed = json.loads(row["metadata_json"])
                if isinstance(parsed, dict):
                    meta_data = parsed
            except json.JSONDecodeError:
                pass
        game_mode = "x2" if meta_data.get("allowDoubling") else "classic"

        # Process turns and events
        # Keys in state_map are "0", "1", "2"...
        keys = sorted([int(k) for k in state_map.keys()])

        # Precompute double decline index if any
        allow_doubling = meta_data.get("allowDoubling", False)
        double_decline_start_index = None
        if allow_doubling and keys:
            max_index = keys[-1]
            trailing_start = None
            for i in range(max_index, 0, -1):
                state = state_map.get(str(i))
                prev_state = state_map.get(str(i - 1))
                if not state or not prev_state:
                    break
                has_no_move = state.get("gameMoveHistoryMove") is None
                dices = state.get("dices") or []
                all_dice_blocked = len(dices) > 0 and all(
                    not d.get("allowed", True) for d in dices
                )
                same_dice_as_prev = are_dice_equal(dices, prev_state.get("dices") or [])

                if has_no_move and all_dice_blocked and same_dice_as_prev:
                    trailing_start = i
                else:
                    break
            if trailing_start is not None and max_index - trailing_start >= 1:
                double_decline_start_index = trailing_start

        current_bank = state_map[str(keys[0])].get("bank", 1000)
        sequence_number = 1

        turn_number = 1

        initial_fen = state_map[str(keys[0])]["fen"]
        norm_initial_fen = normalize_fen(initial_fen)
        initial_hash = get_fen_hash(norm_initial_fen)

        if initial_hash not in positions_cache:
            positions_batch.append(
                {
                    "normalized_fen": norm_initial_fen,
                    "fen_hash": initial_hash,
                    "piece_placement": norm_initial_fen.split(" ")[0],
                    "active_color": norm_initial_fen.split(" ")[1],
                    "castling": norm_initial_fen.split(" ")[2],
                    "en_passant": norm_initial_fen.split(" ")[3],
                }
            )

        final_hash = None

        current_turn_color = None
        current_turn_dices_str = ""
        current_turn_moves = []
        current_turn_pos_hash = None

        # Traverse the state map
        for k in keys:
            state = state_map[str(k)]
            fen = state["fen"]
            color = fen.split(" ")[1]
            dices = state.get("dices") or []
            move = state.get("gameMoveHistoryMove")
            bank = state.get("bank", 1000)
            state.get("leftTime", {})

            # Double Decline Events
            if double_decline_start_index is not None and k == double_decline_start_index:
                offerer_color = color
                decliner_color = "b" if offerer_color == "w" else "w"

                event_turn = turn_number
                if current_turn_color and color != current_turn_color:
                    event_turn = turn_number + 1

                events_batch.append(
                    {
                        "game_id": game_uuid,
                        "sequence_number": sequence_number,
                        "turn_number": event_turn,
                        "event_type": "DOUBLE_OFFER",
                        "actor_color": offerer_color,
                        "clock_white_ms": None,
                        "clock_black_ms": None,
                        "payload": None,
                    }
                )
                sequence_number += 1

                events_batch.append(
                    {
                        "game_id": game_uuid,
                        "sequence_number": sequence_number,
                        "turn_number": event_turn,
                        "event_type": "DOUBLE_DECLINE",
                        "actor_color": decliner_color,
                        "clock_white_ms": None,
                        "clock_black_ms": None,
                        "payload": None,
                    }
                )
                sequence_number += 1

            # Double Accept Events
            if bank > current_bank:
                offerer_color = color
                accepter_color = "b" if offerer_color == "w" else "w"

                event_turn = turn_number
                if current_turn_color and color != current_turn_color:
                    event_turn = turn_number + 1

                events_batch.append(
                    {
                        "game_id": game_uuid,
                        "sequence_number": sequence_number,
                        "turn_number": event_turn,
                        "event_type": "DOUBLE_OFFER",
                        "actor_color": offerer_color,
                        "clock_white_ms": None,
                        "clock_black_ms": None,
                        "payload": None,
                    }
                )
                sequence_number += 1

                events_batch.append(
                    {
                        "game_id": game_uuid,
                        "sequence_number": sequence_number,
                        "turn_number": event_turn,
                        "event_type": "DOUBLE_ACCEPT",
                        "actor_color": accepter_color,
                        "clock_white_ms": None,
                        "clock_black_ms": None,
                        "payload": {"bank": bank},
                    }
                )
                sequence_number += 1
                current_bank = bank

            norm_fen = normalize_fen(fen)
            pos_hash = get_fen_hash(norm_fen)

            if pos_hash not in positions_cache:
                # Add to batch
                if not any(p["fen_hash"] == pos_hash for p in positions_batch):
                    positions_batch.append(
                        {
                            "normalized_fen": norm_fen,
                            "fen_hash": pos_hash,
                            "piece_placement": norm_fen.split(" ")[0],
                            "active_color": color,
                            "castling": norm_fen.split(" ")[2],
                            "en_passant": norm_fen.split(" ")[3],
                        }
                    )

            final_hash = pos_hash

            # Detect robust turn boundaries (Dice Rolls)
            # White dice are uppercase, Black dice are lowercase.
            dices_str = "".join(sorted([d["value"] for d in dices])) if dices else ""
            is_new_roll = False

            if dices_str and not move:
                first_dice = dices[0]["value"]
                is_correct_color = (color == "w" and first_dice.isupper()) or (
                    color == "b" and first_dice.islower()
                )

                if is_correct_color and dices_str != current_turn_dices_str:
                    is_new_roll = True

            # Start of a turn
            if is_new_roll:
                if current_turn_pos_hash is not None:
                    # Save previous turn
                    turns_batch.append(
                        {
                            "game_id": game_uuid,
                            "turn_number": turn_number,
                            "active_color": current_turn_color,
                            "_position_hash": current_turn_pos_hash,
                            "dice_sorted": current_turn_dices_str,
                            "played_moves": current_turn_moves,
                            "_position_after_hash": pos_hash,
                            "thinking_time_ms": None,
                        }
                    )
                    turn_number += 1

                current_turn_color = color
                current_turn_dices_str = dices_str
                current_turn_moves = []
                current_turn_pos_hash = pos_hash

            # Accumulate micro-moves
            elif move:
                move_str = f"{move['from'].lower()}{move['to'].lower()}"
                if move.get("promotion") and move["promotion"] != "NONE":
                    prom_map = {
                        "WHITE_QUEEN": "q",
                        "BLACK_QUEEN": "q",
                        "WHITE_ROOK": "r",
                        "BLACK_ROOK": "r",
                        "WHITE_KNIGHT": "n",
                        "BLACK_KNIGHT": "n",
                        "WHITE_BISHOP": "b",
                        "BLACK_BISHOP": "b",
                    }
                    move_str += prom_map.get(move["promotion"], "q")
                current_turn_moves.append(move_str)

        # Save the last turn if game ended
        if current_turn_pos_hash is not None:
            turns_batch.append(
                {
                    "game_id": game_uuid,
                    "turn_number": turn_number,
                    "active_color": current_turn_color,
                    "_position_hash": current_turn_pos_hash,
                    "dice_sorted": current_turn_dices_str,
                    "played_moves": current_turn_moves,
                    "_position_after_hash": final_hash,
                    "thinking_time_ms": None,
                }
            )
            turn_number += 1

        # Check for DRAW_OFFER and DRAW_ACCEPT heuristically
        if row["result"] == 0 and keys:
            max_index = keys[-1]
            last_state = state_map[str(max_index)]

            # Exclude timeout
            is_timeout = False
            left_time = last_state.get("leftTime") or {}
            if isinstance(left_time, dict) and any(
                isinstance(t, (int, float)) and t <= 0 for t in left_time.values()
            ):
                is_timeout = True

            if not is_timeout:
                trailing_start = None
                accepter_clock_key = None
                for i in range(max_index, 0, -1):
                    state = state_map.get(str(i))
                    prev_state = state_map.get(str(i - 1))
                    if not state or not prev_state:
                        break

                    has_no_move = state.get("gameMoveHistoryMove") is None
                    same_dices = are_dice_equal(state.get("dices"), prev_state.get("dices"))
                    same_fen = state.get("fen") == prev_state.get("fen")

                    if has_no_move and same_dices and same_fen:
                        trailing_start = i

                        # Find who lost time between prev_state and state
                        st_time = state.get("leftTime") or {}
                        prev_time = prev_state.get("leftTime") or {}
                        if isinstance(st_time, dict) and isinstance(prev_time, dict):
                            for tk, tv in st_time.items():
                                if tk in prev_time:
                                    prev_val = prev_time[tk]
                                    if (
                                        isinstance(tv, (int, float))
                                        and isinstance(prev_val, (int, float))
                                        and tv < prev_val
                                    ):
                                        accepter_clock_key = tk
                    else:
                        break

                if trailing_start is not None and accepter_clock_key is not None:
                    w_id_str = (
                        str(row["white_player_id"]) if row["white_player_id"] is not None else None
                    )
                    b_id_str = (
                        str(row["black_player_id"]) if row["black_player_id"] is not None else None
                    )

                    accepter_color = None
                    offerer_color = None

                    if accepter_clock_key in (w_id_str, "white", "w"):
                        accepter_color = "w"
                        offerer_color = "b"
                    elif accepter_clock_key in (b_id_str, "black", "b"):
                        accepter_color = "b"
                        offerer_color = "w"

                    if accepter_color:
                        events_batch.append(
                            {
                                "game_id": game_uuid,
                                "sequence_number": sequence_number,
                                "turn_number": turn_number - 1,
                                "event_type": "DRAW_OFFER",
                                "actor_color": offerer_color,
                                "clock_white_ms": None,
                                "clock_black_ms": None,
                                "payload": None,
                            }
                        )
                        sequence_number += 1

                        events_batch.append(
                            {
                                "game_id": game_uuid,
                                "sequence_number": sequence_number,
                                "turn_number": turn_number - 1,
                                "event_type": "DRAW_ACCEPT",
                                "actor_color": accepter_color,
                                "clock_white_ms": None,
                                "clock_black_ms": None,
                                "payload": None,
                            }
                        )
                        sequence_number += 1

        # Determine White and Black Player UUIDs correctly
        # This requires matching the external_id
        # We will use subquery or fetch them later, but for bulk insert we can just lookup
        # Actually, our Player table id is UUID, external_id is string. We need to query player IDs.
        # This makes it a bit complex for a single pass. Let's simplify and just not link players for now,
        # or just generate deterministic UUIDs based on username.

        # To make it simple, let's generate deterministic UUIDs for players
        w_uuid = uuid.uuid5(uuid.NAMESPACE_OID, w_username) if w_username else None
        b_uuid = uuid.uuid5(uuid.NAMESPACE_OID, b_username) if b_username else None

        # Update players_batch to use these UUIDs
        for p in players_batch:
            if p["username"] == w_username:
                p["id"] = w_uuid
            if p["username"] == b_username:
                p["id"] = b_uuid

        # Extract time and stake
        time_initial_sec = meta_data.get("timeLimit")
        time_increment_sec = meta_data.get("timeBonus")
        stake_currency = meta_data.get("currency")
        money_delta = meta_data.get("moneyDelta")
        delta_color = meta_data.get("color")

        white_money_delta = None
        black_money_delta = None
        if delta_color == "WHITE" and money_delta is not None:
            white_money_delta = float(money_delta)
        elif delta_color == "BLACK" and money_delta is not None:
            black_money_delta = float(money_delta)

        # Calculate initial and final stakes
        initial_stake_amount = None
        final_stake_amount = None
        if keys:
            first_state = state_map[str(keys[0])]
            initial_stake_amount = first_state.get("bank", 1000)
            last_state = state_map[str(keys[-1])]
            final_stake_amount = last_state.get("bank", 1000)

        games_batch.append(
            {
                "id": game_uuid,
                "source": "dicechess.com",
                "white_player_id": w_uuid,
                "black_player_id": b_uuid,
                "white_rating": int(row["white_player_rating"])
                if row["white_player_rating"]
                else None,
                "black_rating": int(row["black_player_rating"])
                if row["black_player_rating"]
                else None,
                "mode": game_mode,
                "result": row["result"],
                "termination": "unknown",
                "_initial_fen_hash": initial_hash,
                "_final_fen_hash": final_hash,
                "total_turns": turn_number - 1,
                "time_initial_sec": time_initial_sec,
                "time_increment_sec": time_increment_sec,
                "initial_stake_amount": initial_stake_amount,
                "final_stake_amount": final_stake_amount,
                "white_money_delta": white_money_delta,
                "black_money_delta": black_money_delta,
                "stake_currency": stake_currency,
                "started_at": datetime.datetime.fromisoformat(row["start_time"])
                if row["start_time"]
                else None,
                "metadata_json": meta_data if meta_data else None,
            }
        )

        processed_count += 1

        if processed_count % batch_size == 0:
            flush_batches()

        if limit_games and processed_count >= limit_games:
            print(f"Reached limit of {limit_games} games. Stopping.")
            break

    # Final flush
    flush_batches()

    print(f"Finished. Processed: {processed_count}, Skipped (Bots): {skipped_bots}")


if __name__ == "__main__":
    main()
