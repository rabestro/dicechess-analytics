from datetime import datetime
from decimal import Decimal
from typing import Any, List, Optional
from uuid import UUID

from pydantic import BaseModel, ConfigDict

from src.models import GameEventType


class PlayerBase(BaseModel):
    """
    Base Pydantic model for a Player.

    Used to serialize and deserialize basic player information for API responses.
    """

    id: UUID
    username: Optional[str] = None
    player_type: str
    rating_classic: Optional[int] = None

    model_config = ConfigDict(from_attributes=True)


class GameBase(BaseModel):
    """
    Base Pydantic model for a Game.

    Contains summary information about a game without detailed turns or events.
    Useful for list endpoints and basic game metadata retrieval.
    """

    id: UUID
    source: str
    mode: str
    result: Optional[int] = None
    total_turns: Optional[int] = None
    started_at: Optional[datetime] = None
    white_rating: Optional[int] = None
    black_rating: Optional[int] = None
    time_initial_sec: Optional[int] = None
    time_increment_sec: Optional[int] = None
    initial_stake_amount: Optional[int] = None
    final_stake_amount: Optional[int] = None
    white_money_delta: Optional[Decimal] = None
    black_money_delta: Optional[Decimal] = None
    stake_currency: Optional[str] = None
    white_player: Optional[PlayerBase] = None
    black_player: Optional[PlayerBase] = None

    model_config = ConfigDict(from_attributes=True)


class TurnBase(BaseModel):
    """
    Base Pydantic model for a Turn.

    Represents a single turn's details, including dice thrown, moves played,
    and associated position FEN strings before and after the turn.
    """

    turn_number: int
    active_color: str
    dice_sorted: str
    played_moves: Optional[List[str]] = None
    thinking_time_ms: Optional[int] = None
    position_fen: Optional[str] = None
    position_after_fen: Optional[str] = None

    model_config = ConfigDict(from_attributes=True)


class GameEventBase(BaseModel):
    """
    Base Pydantic model for a GameEvent.

    Represents non-move events like doubling stakes or draw offers,
    including the clock times at the moment of the event.
    """

    id: int
    sequence_number: int
    turn_number: Optional[int] = None
    event_type: GameEventType
    actor_color: Optional[str] = None
    clock_white_ms: Optional[int] = None
    clock_black_ms: Optional[int] = None
    payload: Optional[Any] = None

    model_config = ConfigDict(from_attributes=True)


class GameDetail(GameBase):
    """
    Detailed Pydantic model for a Game.

    Inherits from GameBase and adds full collections of associated turns
    and game events. Used for fetching comprehensive data of a single game.
    """

    metadata_json: Optional[Any] = None
    turns: List[TurnBase] = []
    events: List[GameEventBase] = []

    model_config = ConfigDict(from_attributes=True)
