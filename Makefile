.DEFAULT_GOAL := help

SHELL := /bin/bash

# Tools (override if needed)
MVN ?= mvn
ANSIBLE_DIR ?= ansible

# Optional extra args
MAVEN_ARGS ?=
# Ansible host selection:
# - Use HOST for a single host
# - Use HOSTS for multiple hosts (space-separated)
# Both are passed as positional args to the underlying scripts.
HOST ?=
HOSTS ?=
ANSIBLE_HOST_ARGS := $(strip $(HOSTS) $(HOST))

.PHONY: help \
	clean compile package format \
	ansible-deploy ansible-restart ansible-stop ansible-rolling-restart \
	hosts

help:
	@echo "Targets:"
	@echo "  make clean                      -> mvn clean"
	@echo "  make compile                     -> mvn clean compile -DskipTests"
	@echo "  make package                     -> mvn clean package -DskipTests"
	@echo "  make format                      -> mvn spotless:apply"
	@echo ""
	@echo "  make ansible-deploy HOST=node1   -> deploy to one host"
	@echo "  make ansible-deploy HOSTS='n1 n2'-> deploy to multiple hosts"
	@echo "  make ansible-restart HOST=node1  -> restart one host"
	@echo "  make ansible-stop HOST=node1     -> stop one host"
	@echo "  make ansible-stop-node1          -> shortcut for single host"
	@echo "  make ansible-rolling-restart     -> cd ansible && rolling-restart-hiba-service.sh"
	@echo ""
	@echo "Examples:"
	@echo "  make compile"
	@echo "  make package MAVEN_ARGS='-Dspotless.check.skip=true'"
	@echo "  make ansible-deploy HOSTS='node1 node2'"
	@echo "  make ansible-restart HOSTS='accounting-server'"
	@echo "  make hosts"

clean:
	$(MVN) clean $(MAVEN_ARGS)

compile:
	$(MVN) clean compile -DskipTests $(MAVEN_ARGS)

package:
	$(MVN) clean package -DskipTests $(MAVEN_ARGS)

format:
	$(MVN) spotless:apply

hosts:
	@echo "Available hosts (from $(ANSIBLE_DIR)/hosts.ini):"
	@grep -E "^[A-Za-z0-9._-]+[[:space:]]+ansible_host=" "$(ANSIBLE_DIR)/hosts.ini" | awk '{print "  " $$1}'

ansible-deploy:
	cd "$(ANSIBLE_DIR)" && bash ./deploy-run-hiba-service.sh $(ANSIBLE_HOST_ARGS)

ansible-restart:
	cd "$(ANSIBLE_DIR)" && bash ./restart-hiba-service.sh $(ANSIBLE_HOST_ARGS)

ansible-stop:
	cd "$(ANSIBLE_DIR)" && bash ./stop-hiba-service.sh $(ANSIBLE_HOST_ARGS)

ansible-rolling-restart:
	cd "$(ANSIBLE_DIR)" && bash ./rolling-restart-hiba-service.sh

# Convenience shortcuts for single-host operations:
#   make ansible-stop-node1
#   make ansible-restart-accounting-server
ansible-deploy-%:
	@$(MAKE) ansible-deploy HOST="$*"

ansible-restart-%:
	@$(MAKE) ansible-restart HOST="$*"

ansible-stop-%:
	@$(MAKE) ansible-stop HOST="$*"
