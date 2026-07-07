# Load .env if it exists
ifneq (,$(wildcard .env))
include .env
export
endif

LOCAL_IMAGE := $(IMAGE_NAME):$(IMAGE_TAG)
REMOTE_IMAGE := $(DOCKER_USERNAME_TAG)/$(IMAGE_NAME):$(IMAGE_TAG)

.PHONY: login build tag push deploy clean delete-hub all

# Login Docker Hub
login:
	@echo "Make Logging in to Docker Hub..."
	@echo "$(DOCKER_TOKEN)" | docker login -u "$(DOCKER_USERNAME_LOGIN)" --password-stdin


# Build Image
build:
	@echo "Building image..."
	docker build -t $(LOCAL_IMAGE) .

# Tag Image
tag:
	@echo "Tagging image..."
	docker tag $(LOCAL_IMAGE) $(REMOTE_IMAGE)

# Push Image
push: build tag
	@echo "Pushing image..."
	docker push $(REMOTE_IMAGE)


# Remove Local Images
clean:
	@echo "Removing local images..."

	-docker rmi $(LOCAL_IMAGE)
	-docker rmi $(REMOTE_IMAGE)

	@echo "Cleaning build cache..."

	-docker builder prune -f
	-docker image prune -f

# Complete Pipeline
all:
	@set -e; \
	echo "===================================="; \
	echo "BUILD"; \
	echo "===================================="; \
	$(MAKE) build; \
	\
	echo "===================================="; \
	echo "TAG"; \
	echo "===================================="; \
	$(MAKE) tag; \
	\
	echo "===================================="; \
	echo "PUSH"; \
	echo "===================================="; \
	$(MAKE) push; \
	\
	echo "===================================="; \
	echo "CLEAN"; \
	echo "===================================="; \
	$(MAKE) clean; \
	\
	echo "Pipeline completed successfully"

# Build + Push + Deploy
release:
	@set -e; \
	$(MAKE) push; \
	$(MAKE) deploy; \
	$(MAKE) clean

# Delete Tag From Docker Hub
delete-hub:
	@if [ -z "$(DOCKER_TOKEN)" ]; then \
		echo "DOCKER_TOKEN is required"; \
		exit 1; \
	fi

	curl -X DELETE \
		-H "Authorization: Bearer $(DOCKER_TOKEN)" \
		https://hub.docker.com/v2/repositories/$(DOCKER_USERNAME_TAG)/$(IMAGE_NAME)/tags/$(IMAGE_TAG)/

	@echo ""
	@echo "Deleted tag $(IMAGE_TAG)"