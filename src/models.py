import uuid

from sqlalchemy import (
    BigInteger,
    Column,
    DateTime,
    ForeignKey,
    Integer,
    SmallInteger,
    String,
)
from sqlalchemy.dialects.postgresql import ARRAY, JSONB, UUID
from sqlalchemy.orm import relationship
from sqlalchemy.sql import func

from src.database import Base


class Position(Base):
    __tablename__ = "positions"

    id = Column(BigInteger, primary_key=True, autoincrement=True)
    normalized_fen = Column(String(100), nullable=False, unique=True)
    fen_hash = Column(BigInteger, nullable=False, index=True)
    piece_placement = Column(String(72), nullable=False, index=True)
    active_color = Column(String(1), nullable=False)
    castling = Column(String(4), nullable=False, server_default="-")
    en_passant = Column(String(12), nullable=False, server_default="-")


class Player(Base):
    __tablename__ = "players"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    external_id = Column(String(50), unique=True, nullable=True)
    username = Column(String(50), nullable=True)
    player_type = Column(String(10), nullable=False, server_default="human")
    rating_classic = Column(Integer, nullable=True)
    rating_x2 = Column(Integer, nullable=True)
    metadata_json = Column(JSONB, nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now(), nullable=False)
    updated_at = Column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )


class Game(Base):
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
    mode = Column(String(10), nullable=False, server_default="classic")
    result = Column(SmallInteger, nullable=True)
    termination = Column(
        String(20), nullable=True
    )  # Will use CheckConstraint or Enum in real DB, defined as String here
    initial_position_id = Column(BigInteger, ForeignKey("positions.id"), nullable=True)
    final_position_id = Column(BigInteger, ForeignKey("positions.id"), nullable=True)
    total_turns = Column(SmallInteger, nullable=True)
    started_at = Column(DateTime(timezone=True), nullable=True, index=True)
    metadata_json = Column(JSONB, nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now(), nullable=False)


class Turn(Base):
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
    __tablename__ = "game_events"

    id = Column(BigInteger, primary_key=True, autoincrement=True)
    game_id = Column(
        UUID(as_uuid=True), ForeignKey("games.id", ondelete="CASCADE"), nullable=False
    )
    sequence_number = Column(SmallInteger, nullable=False)
    turn_number = Column(SmallInteger, nullable=True)
    event_type = Column(String(20), nullable=False)
    actor_color = Column(String(1), nullable=True)
    clock_white_ms = Column(Integer, nullable=True)
    clock_black_ms = Column(Integer, nullable=True)
    payload = Column(JSONB, nullable=True)
