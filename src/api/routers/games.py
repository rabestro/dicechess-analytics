from typing import List, Optional
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from src.api.dependencies import get_db
from src.api.schemas import GameBase, GameDetail
from src.models import Game, Position, Turn

router = APIRouter(prefix="/api/games", tags=["Games"])


@router.get("", response_model=List[GameBase])
async def list_games(
    player_id: Optional[UUID] = Query(
        None, description="Filter by player UUID (either white or black)"
    ),
    min_turns: Optional[int] = Query(None, description="Minimum number of turns in the game"),
    limit: int = Query(50, le=200),
    offset: int = Query(0, ge=0, description="Number of games to skip"),
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

    if offset:
        query = query.offset(offset)
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
