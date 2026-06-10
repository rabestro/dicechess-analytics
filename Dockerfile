# syntax=docker/dockerfile:1

FROM python:3.12-slim

# Set environment variables
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    UV_SYSTEM_PYTHON=1

# Set the working directory
WORKDIR /app

# Install system dependencies (if any needed, e.g. for building extensions)
# RUN apt-get update && apt-get install -y --no-install-recommends gcc libpq-dev && rm -rf /var/lib/apt/lists/*

# Install uv for fast package installation
RUN pip install uv

# Copy uv.lock and pyproject.toml
COPY pyproject.toml uv.lock ./

# Install dependencies using uv
RUN uv sync --no-dev --frozen

# Copy the application code
COPY src/ ./src/
COPY alembic/ ./alembic/
COPY alembic.ini ./

# Expose the API port
EXPOSE 8000

# Set the entrypoint to run migrations and then start the API
CMD ["sh", "-c", "uv run alembic upgrade head && uv run uvicorn src.api.main:app --host 0.0.0.0 --port 8000 --proxy-headers"]
