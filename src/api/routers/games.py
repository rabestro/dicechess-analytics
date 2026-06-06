from typing import List, Optional
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from src.api.dependencies import get_db
from src.api.schemas import GameBase, GameCreate, GameDetail
from src.models import Game, Player, Position, Turn

router = APIRouter(prefix="/api/games", tags=["Games"])


@router.get("", response_model=List[GameBase])
async def list_games(
    player_id: Optional[UUID] = Query(
        None, description="Filter by player UUID (either white or black)"
    ),
    min_turns: Optional[int] = Query(None, description="Minimum number of turns in the game"),
    limit: int = Query(50, le=200),
    db: AsyncSession = Depends(get_db),
):
    """
    List and filter games.
    """
    query = (
        select(Game)
        .options(selectinload(Game.white_player), selectinload(Game.black_player))
        .order_by(Game.started_at.desc().nulls_last())
    )

    if player_id:
        query = query.filter(
            (Game.white_player_id == player_id) | (Game.black_player_id == player_id)
        )
    if min_turns:
        query = query.filter(Game.total_turns >= min_turns)

    query = query.limit(limit)

    result = await db.execute(query)
    games = result.scalars().all()
    return games


@router.get("/{game_id}", response_model=GameDetail)
async def get_game(game_id: UUID, db: AsyncSession = Depends(get_db)):
    """
    Get detailed information about a game, including its turns and positions.
    """
    query = (
        select(Game)
        .options(selectinload(Game.white_player), selectinload(Game.black_player))
        .filter(Game.id == game_id)
    )

    result = await db.execute(query)
    game = result.scalar_one_or_none()

    if not game:
        raise HTTPException(status_code=404, detail="Game not found")

    # Fetch turns for this game
    turns_query = (
        select(Turn, Position.normalized_fen)
        .join(Position, Turn.position_id == Position.id)
        .filter(Turn.game_id == game_id)
        .order_by(Turn.turn_number.asc())
    )

    turns_result = await db.execute(turns_query)

    turns_data = []
    for turn, fen in turns_result.all():
        turn_dict = {
            "turn_number": turn.turn_number,
            "active_color": turn.active_color,
            "dice_sorted": turn.dice_sorted,
            "played_moves": turn.played_moves,
            "thinking_time_ms": turn.thinking_time_ms,
            "position_fen": fen,
        }
        turns_data.append(turn_dict)

    # Convert SQLAlchemy model to Pydantic compatible dict
    game_dict = {
        "id": game.id,
        "source": game.source,
        "mode": game.mode,
        "result": game.result,
        "total_turns": game.total_turns,
        "started_at": game.started_at,
        "metadata_json": game.metadata_json,
        "white_player": game.white_player,
        "black_player": game.black_player,
        "turns": turns_data,
    }

    return game_dict


def normalize_fen(fen_str: str) -> str:
    """Extracts piece placement, color, castling, and en_passant for deduplication."""
    parts = fen_str.strip().split(" ")
    # FEN has at least 4 fields. If less, pad them
    while len(parts) < 4:
        parts.append("-")
    return " ".join(parts[0:4])


def get_fen_hash(normalized_fen: str) -> int:
    """Generates an xxhash64 integer digest."""
    import xxhash

    digest = xxhash.xxh64(normalized_fen).intdigest()
    if digest >= 2**63:
        digest -= 2**64
    return digest


@router.post("", response_model=GameDetail)
async def create_game(game_in: GameCreate, db: AsyncSession = Depends(get_db)):
    """
    Save a completed local/bot game, its turns, and resolve positions.
    """

    # 1. Resolve players
    async def get_or_create_player(username: str, player_type: str) -> Player:
        stmt = select(Player).filter(
            Player.username == username, Player.player_type == player_type
        )
        result = await db.execute(stmt)
        player = result.scalar_one_or_none()
        if not player:
            player = Player(
                username=username,
                player_type=player_type,
            )
            db.add(player)
            await db.flush()
        return player

    white_player = await get_or_create_player(
        game_in.white_player.username, game_in.white_player.player_type
    )
    black_player = await get_or_create_player(
        game_in.black_player.username, game_in.black_player.player_type
    )

    # 2. Resolve positions
    position_cache = {}

    async def get_or_create_position(fen_str: str) -> Position:
        norm_fen = normalize_fen(fen_str)
        fen_hash = get_fen_hash(norm_fen)

        if norm_fen in position_cache:
            return position_cache[norm_fen]

        stmt = select(Position).filter(Position.normalized_fen == norm_fen)
        res = await db.execute(stmt)
        pos = res.scalar_one_or_none()
        if not pos:
            pos = Position(
                normalized_fen=norm_fen,
                fen_hash=fen_hash,
                piece_placement=norm_fen.split(" ")[0],
                active_color=norm_fen.split(" ")[1],
                castling=norm_fen.split(" ")[2],
                en_passant=norm_fen.split(" ")[3],
            )
            db.add(pos)
            await db.flush()

        position_cache[norm_fen] = pos
        return pos

    # Generate game ID
    import uuid

    game_uuid = uuid.uuid4()

    # Pre-process turns to find positions
    db_turns = []
    initial_pos = None
    final_pos = None

    for i, t in enumerate(game_in.turns):
        pos_before = await get_or_create_position(t.position_fen)
        pos_after = await get_or_create_position(t.position_after_fen)

        if i == 0:
            initial_pos = pos_before
        final_pos = pos_after

        db_turn = Turn(
            game_id=game_uuid,
            turn_number=t.turn_number,
            active_color=t.active_color,
            position_id=pos_before.id,
            dice_sorted=t.dice_sorted,
            played_moves=t.played_moves,
            position_after_id=pos_after.id,
            thinking_time_ms=t.thinking_time_ms,
        )
        db_turns.append(db_turn)

    # Create Game record
    import datetime

    started_at = game_in.started_at or datetime.datetime.now(datetime.timezone.utc)

    game = Game(
        id=game_uuid,
        source=game_in.source,
        mode=game_in.mode,
        white_player_id=white_player.id,
        black_player_id=black_player.id,
        result=game_in.result,
        termination=game_in.termination or "normal",
        initial_position_id=initial_pos.id if initial_pos else None,
        final_position_id=final_pos.id if final_pos else None,
        total_turns=len(db_turns),
        started_at=started_at,
        metadata_json={},
    )

    db.add(game)
    for db_turn in db_turns:
        db.add(db_turn)

    await db.commit()

    # Refresh game with relationships
    query = (
        select(Game)
        .options(selectinload(Game.white_player), selectinload(Game.black_player))
        .filter(Game.id == game_uuid)
    )
    result = await db.execute(query)
    game = result.scalar_one()

    # Map turns_data to return
    turns_data = []
    for t in game_in.turns:
        turns_data.append(
            {
                "turn_number": t.turn_number,
                "active_color": t.active_color,
                "dice_sorted": t.dice_sorted,
                "played_moves": t.played_moves,
                "thinking_time_ms": t.thinking_time_ms,
                "position_fen": t.position_fen,
            }
        )

    return {
        "id": game.id,
        "source": game.source,
        "mode": game.mode,
        "result": game.result,
        "total_turns": game.total_turns,
        "started_at": game.started_at,
        "metadata_json": game.metadata_json,
        "white_player": game.white_player,
        "black_player": game.black_player,
        "turns": turns_data,
    }
