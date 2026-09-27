COMPOSE ?= docker compose
SERVICE ?= paper

.PHONY: help version package verify-release restart-test-server up down logs status plugins

help:
	@printf '%s\n' \
		'Targets:' \
		'  version              Print the application version' \
		'  package              Build the versioned plugin JAR' \
		'  verify-release       Build and verify all release metadata' \
		'  restart-test-server  Rebuild the image from current source and restart Paper' \
		'  up                   Start the local Paper test server' \
		'  down                 Stop the local Paper test server' \
		'  logs                 Follow Paper logs' \
		'  status               Show compose service status' \
		'  plugins              List loaded server plugins through RCON'

version:
	@mvn help:evaluate -Dexpression=project.version -q -DforceStdout

package:
	mvn clean package

verify-release:
	./scripts/verify-release.sh

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
