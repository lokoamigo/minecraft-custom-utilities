COMPOSE ?= docker compose
DOCKER ?= docker
SERVICE ?= paper
LEGACY_CONTAINER ?= fastminecarts-paper
LEGACY_PLUGIN_JAR ?= /data/plugins/FastMinecarts.jar
PAPER_DATA_VOLUME ?= mc-faster-minecarts_paper-data

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
	$(DOCKER) volume create $(PAPER_DATA_VOLUME)
	$(COMPOSE) build $(SERVICE)
	-$(DOCKER) rm --force $(LEGACY_CONTAINER)
	$(COMPOSE) run --rm --no-deps --entrypoint rm $(SERVICE) -f $(LEGACY_PLUGIN_JAR)
	$(COMPOSE) up -d --force-recreate $(SERVICE)

up:
	$(DOCKER) volume create $(PAPER_DATA_VOLUME)
	$(COMPOSE) up -d $(SERVICE)

down:
	$(COMPOSE) down

logs:
	$(COMPOSE) logs -f $(SERVICE)

status:
	$(COMPOSE) ps

plugins:
	$(COMPOSE) exec $(SERVICE) rcon-cli plugins
