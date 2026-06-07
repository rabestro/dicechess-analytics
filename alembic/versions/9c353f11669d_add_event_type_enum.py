"""add_event_type_enum

Revision ID: 9c353f11669d
Revises: af29eb457ede
Create Date: 2026-06-07 22:53:50.563022

"""

from typing import Sequence, Union

import sqlalchemy as sa

from alembic import op

# revision identifiers, used by Alembic.
revision: str = "9c353f11669d"
down_revision: Union[str, Sequence[str], None] = "af29eb457ede"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Create the enum type first
    game_event_type_enum = sa.Enum(
        "DOUBLE_OFFER",
        "DOUBLE_ACCEPT",
        "DOUBLE_DECLINE",
        "DRAW_OFFER",
        "DRAW_ACCEPT",
        name="game_event_type_enum",
    )
    game_event_type_enum.create(op.get_bind(), checkfirst=True)

    op.alter_column(
        "game_events",
        "event_type",
        existing_type=sa.VARCHAR(length=20),
        type_=game_event_type_enum,
        existing_nullable=False,
        postgresql_using="event_type::game_event_type_enum",
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.alter_column(
        "game_events",
        "event_type",
        existing_type=sa.Enum(
            "DOUBLE_OFFER",
            "DOUBLE_ACCEPT",
            "DOUBLE_DECLINE",
            "DRAW_OFFER",
            "DRAW_ACCEPT",
            name="game_event_type_enum",
        ),
        type_=sa.VARCHAR(length=20),
        existing_nullable=False,
        postgresql_using="event_type::varchar",
    )

    op.execute("DROP TYPE IF EXISTS game_event_type_enum")
