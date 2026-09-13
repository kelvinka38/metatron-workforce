FROM python:3.13-slim

RUN useradd --system --uid 10002 --create-home cognition
WORKDIR /app
COPY cognition-node/server.py /app/server.py
RUN chmod 0555 /app/server.py
USER 10002:10002
EXPOSE 8091
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENTRYPOINT ["python3","/app/server.py"]
