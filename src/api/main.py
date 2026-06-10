"""
Main entry point for the FastAPI web application.
"""

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from src.api.routers import games, players
from src.config import settings

app = FastAPI(
    title="Dice Chess Analytics API",
    description="Backend API for querying and analyzing Dice Chess games and positions.",
    version="1.0.0",
)

# Allow CORS configured via settings
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(games.router)
app.include_router(players.router)


@app.get("/")
async def root():
    """Welcome endpoint providing basic API information."""
    return {"message": "Welcome to Dice Chess Analytics API", "docs": "/docs"}
