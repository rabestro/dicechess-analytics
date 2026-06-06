from typing import List
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from src.api.dependencies import get_db
from src.api.schemas import PlayerBase
from src.models import Player

router = APIRouter(prefix="/api/players", tags=["Players"])


@router.get("", response_model=List[PlayerBase])
async def list_players(
    username: str = Query(None, description="Filter by username"),
    limit: int = Query(50, le=100),
    db: AsyncSession = Depends(get_db),
):
    """
    List players, optionally filtering by username or ordering by rating.
    """
    query = select(Player).order_by(Player.rating_classic.desc().nulls_last())

    if username:
        query = query.filter(Player.username.ilike(f"%{username}%"))

    query = query.limit(limit)

    result = await db.execute(query)
    players = result.scalars().all()
    return players


@router.get("/{player_id}", response_model=PlayerBase)
async def get_player(player_id: UUID, db: AsyncSession = Depends(get_db)):
    """
    Get a specific player by UUID.
    """
    result = await db.execute(select(Player).filter(Player.id == player_id))
    player = result.scalar_one_or_none()
    if not player:
        raise HTTPException(status_code=404, detail="Player not found")
    return player
