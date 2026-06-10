"""
Database connection and session management for SQLAlchemy.
"""

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import declarative_base

# Default to the local docker-compose setup
DATABASE_URL = (
    "postgresql+asyncpg://dicechess_user:dicechess_password@localhost:5432/dicechess_analytics"
)

engine = create_async_engine(DATABASE_URL, echo=False)
AsyncSessionLocal = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)

Base = declarative_base()


async def get_db():
    """Dependency for providing an async database session."""
    async with AsyncSessionLocal() as session:
        yield session
