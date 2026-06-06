from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine
from sqlalchemy.orm import sessionmaker

# Define the async engine here for the API
PG_URL = (
    "postgresql+asyncpg://dicechess_user:dicechess_password@localhost:5432/dicechess_analytics"
)
engine = create_async_engine(PG_URL, echo=False)
AsyncSessionLocal = sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)


async def get_db():
    """Dependency for providing an async database session."""
    async with AsyncSessionLocal() as session:
        yield session
