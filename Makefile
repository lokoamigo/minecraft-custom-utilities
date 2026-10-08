COMPOSE ?= docker compose
DOCKER ?= docker
SERVICE ?= paper
LEGACY_CONTAINER ?= fastminecarts-paper
LEGACY_PLUGIN_JAR ?= /data/plugins/FastMinecarts.jar
PAPER_DATA_VOLUME ?= mc-faster-minecarts_paper-data

.PHONY: help version package verify-release restart-test-server verify-test-server up down logs status plugins

help:
	@printf '%s\n' \
		'Targets:' \
		'  version              Print the application version' \
		'  package              Build the versioned plugin JAR' \
		'  verify-release       Build and verify all release metadata' \
		'  restart-test-server  Rebuild, restart, and health-check Paper' \
		'  verify-test-server   Check Paper health, plugin loading, and startup logs' \
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
	$(MAKE) verify-test-server

verify-test-server:
	@container_id="$$($(COMPOSE) ps -q $(SERVICE))"; \
	if [ -z "$$container_id" ]; then \
		echo "Paper container is not running."; \
		exit 1; \
	fi; \
	attempts=0; \
	until [ "$$($(DOCKER) inspect --format '{{.State.Health.Status}}' "$$container_id")" = "healthy" ]; do \
		attempts=$$((attempts + 1)); \
		if [ "$$attempts" -ge 60 ]; then \
			echo "Paper did not become healthy within 120 seconds."; \
			$(COMPOSE) logs --tail=200 $(SERVICE); \
			exit 1; \
		fi; \
		sleep 2; \
	done
	$(COMPOSE) exec $(SERVICE) rcon-cli plugins | grep MinecraftCustomUtilities
	@if $(COMPOSE) logs $(SERVICE) | grep -E '\[MinecraftCustomUtilities\].*(WARN|ERROR|SEVERE|Exception)'; then \
		echo "MinecraftCustomUtilities reported startup problems."; \
		exit 1; \
	fi
	$(COMPOSE) ps

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
