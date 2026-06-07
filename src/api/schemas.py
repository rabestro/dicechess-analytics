from datetime import datetime
from typing import Any, List, Optional
from uuid import UUID

from pydantic import BaseModel, ConfigDict


class PlayerBase(BaseModel):
    id: UUID
    username: Optional[str] = None
    player_type: str
    rating_classic: Optional[int] = None

    model_config = ConfigDict(from_attributes=True)


class GameBase(BaseModel):
    id: UUID
    source: str
    mode: str
    result: Optional[int] = None
    total_turns: Optional[int] = None
    started_at: Optional[datetime] = None
    white_rating: Optional[int] = None
    black_rating: Optional[int] = None
    white_player: Optional[PlayerBase] = None
    black_player: Optional[PlayerBase] = None

    model_config = ConfigDict(from_attributes=True)


class TurnBase(BaseModel):
    turn_number: int
    active_color: str
    dice_sorted: str
    played_moves: Optional[List[str]] = None
    thinking_time_ms: Optional[int] = None
    position_fen: Optional[str] = None
    position_after_fen: Optional[str] = None

    model_config = ConfigDict(from_attributes=True)


class GameDetail(GameBase):
    metadata_json: Optional[Any] = None
    turns: List[TurnBase] = []

    model_config = ConfigDict(from_attributes=True)
