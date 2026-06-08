import enum
import uuid

from sqlalchemy import (
    BigInteger,
    Column,
    DateTime,
    Enum,
    ForeignKey,
    Integer,
    Numeric,
    SmallInteger,
    String,
)
from sqlalchemy.dialects.postgresql import ARRAY, JSONB, UUID
from sqlalchemy.orm import relationship
from sqlalchemy.sql import func

from src.database import Base


class GameEventType(str, enum.Enum):
    """
    Enum representing various events that can occur during a game.

    Attributes:
        DOUBLE_OFFER (str): A player offered a double.
        DOUBLE_ACCEPT (str): A player accepted a double offer.
        DOUBLE_DECLINE (str): A player declined a double offer.
        DRAW_OFFER (str): A player offered a draw.
        DRAW_ACCEPT (str): A player accepted a draw offer.
    """

    DOUBLE_OFFER = "DOUBLE_OFFER"
    DOUBLE_ACCEPT = "DOUBLE_ACCEPT"
    DOUBLE_DECLINE = "DOUBLE_DECLINE"
    DRAW_OFFER = "DRAW_OFFER"
    DRAW_ACCEPT = "DRAW_ACCEPT"


class Position(Base):
    """
    Represents a unique chess board position with associated metadata.

    This table stores normalized FENs to deduplicate identical board states
    across multiple games, enabling efficient position analytics.
    """

    __tablename__ = "positions"

    id = Column(BigInteger, primary_key=True, autoincrement=True)
    normalized_fen = Column(String(100), nullable=False, unique=True)
    fen_hash = Column(BigInteger, nullable=False, index=True)
    piece_placement = Column(String(72), nullable=False, index=True)
    active_color = Column(String(1), nullable=False)
    castling = Column(String(4), nullable=False, server_default="-")
    en_passant = Column(String(12), nullable=False, server_default="-")


class Player(Base):
    """
    Represents a player in the Dice Chess system.

    Stores player identity, rating, and metadata. External ID can be
    used to link to accounts from external services.
    """

    __tablename__ = "players"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    external_id = Column(String(50), unique=True, nullable=True)
    username = Column(String(50), nullable=True)
    player_type = Column(String(10), nullable=False, server_default="human")
    metadata_json = Column(JSONB, nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now(), nullable=False)
    updated_at = Column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )


class Game(Base):
    """
    Represents a single completed Dice Chess game.

    Stores the overall game metadata, results, time controls, and financial stakes.
    Individual turns and events for the game are stored in related tables.
    """

    __tablename__ = "games"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    source = Column(String(20), nullable=False)
    white_player_id = Column(
        UUID(as_uuid=True), ForeignKey("players.id"), nullable=True, index=True
    )
    black_player_id = Column(
        UUID(as_uuid=True), ForeignKey("players.id"), nullable=True, index=True
    )
    white_player = relationship("Player", foreign_keys=[white_player_id])
    black_player = relationship("Player", foreign_keys=[black_player_id])
    white_rating = Column(Integer, nullable=True)
    black_rating = Column(Integer, nullable=True)
    mode = Column(String(10), nullable=False, server_default="classic")
    result = Column(SmallInteger, nullable=True)
    termination = Column(
        String(20), nullable=True
    )  # Will use CheckConstraint or Enum in real DB, defined as String here
    initial_position_id = Column(BigInteger, ForeignKey("positions.id"), nullable=True)
    final_position_id = Column(BigInteger, ForeignKey("positions.id"), nullable=True)
    total_turns = Column(SmallInteger, nullable=True)
    time_initial_sec = Column(Integer, nullable=True)
    time_increment_sec = Column(Integer, nullable=True)
    initial_stake_amount = Column(Integer, nullable=True)
    final_stake_amount = Column(Integer, nullable=True)
    white_money_delta = Column(Numeric, nullable=True)
    black_money_delta = Column(Numeric, nullable=True)
    stake_currency = Column(String(20), nullable=True)
    started_at = Column(DateTime(timezone=True), nullable=True, index=True)
    metadata_json = Column(JSONB, nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now(), nullable=False)


class Turn(Base):
    """
    Represents a single turn within a game.

    A turn typically consists of a dice roll and subsequent moves played by the active player.
    It references the board position before and after the turn.
    """

    __tablename__ = "turns"

    id = Column(BigInteger, primary_key=True, autoincrement=True)
    game_id = Column(
        UUID(as_uuid=True), ForeignKey("games.id", ondelete="CASCADE"), nullable=False
    )
    turn_number = Column(SmallInteger, nullable=False)
    active_color = Column(String(1), nullable=False)
    position_id = Column(BigInteger, ForeignKey("positions.id"), nullable=False, index=True)
    dice_sorted = Column(String(3), nullable=False)
    played_moves = Column(ARRAY(String(5)), nullable=True)
    position_after_id = Column(BigInteger, ForeignKey("positions.id"), nullable=True)
    thinking_time_ms = Column(Integer, nullable=True)


class GameEvent(Base):
    """
    Represents an event that occurred during a game outside of standard move play.

    Used for tracking offers, acceptances, and declines (e.g., doubling stakes, draws).
    """

    __tablename__ = "game_events"

    id = Column(BigInteger, primary_key=True, autoincrement=True)
    game_id = Column(
        UUID(as_uuid=True), ForeignKey("games.id", ondelete="CASCADE"), nullable=False
    )
    sequence_number = Column(SmallInteger, nullable=False)
    turn_number = Column(SmallInteger, nullable=True)
    event_type = Column(Enum(GameEventType, name="game_event_type_enum"), nullable=False)
    actor_color = Column(String(1), nullable=True)
    clock_white_ms = Column(Integer, nullable=True)
    clock_black_ms = Column(Integer, nullable=True)
    payload = Column(JSONB, nullable=True)
