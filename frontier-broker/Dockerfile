FROM python:3.13-slim

RUN useradd --system --uid 10003 --create-home frontier
WORKDIR /app
COPY frontier-broker/server.py /app/server.py
RUN chmod 0555 /app/server.py
USER 10003:10003
EXPOSE 8092
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENTRYPOINT ["python3","/app/server.py"]
