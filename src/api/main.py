from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from src.api.routers import games, players

app = FastAPI(
    title="Dice Chess Analytics API",
    description="Backend API for querying and analyzing Dice Chess games and positions.",
    version="1.0.0",
)

# Allow CORS for local frontend development
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # In production, replace with specific domains
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(games.router)
app.include_router(players.router)


@app.get("/")
async def root():
    return {"message": "Welcome to Dice Chess Analytics API", "docs": "/docs"}
