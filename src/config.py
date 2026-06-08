from typing import List

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Application configuration settings, loaded from environment variables."""

    # Database Configuration
    DATABASE_URL: str = (
        "postgresql+asyncpg://dicechess_user:dicechess_password@localhost:5432/dicechess_analytics"
    )

    # API Configuration
    CORS_ORIGINS: List[str] = ["*"]

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")


settings = Settings()
