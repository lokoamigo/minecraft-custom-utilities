COMPOSE ?= docker compose
SERVICE ?= paper

.PHONY: help restart-test-server up down logs status plugins

help:
	@printf '%s\n' \
		'Targets:' \
		'  restart-test-server  Rebuild the image from current source and restart Paper' \
		'  up                   Start the local Paper test server' \
		'  down                 Stop the local Paper test server' \
		'  logs                 Follow Paper logs' \
		'  status               Show compose service status' \
		'  plugins              List loaded server plugins through RCON'

restart-test-server:
	$(COMPOSE) up --build -d $(SERVICE)

up:
	$(COMPOSE) up -d $(SERVICE)

down:
	$(COMPOSE) down

logs:
	$(COMPOSE) logs -f $(SERVICE)

status:
	$(COMPOSE) ps

plugins:
	$(COMPOSE) exec $(SERVICE) rcon-cli plugins
