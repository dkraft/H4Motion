ENV ?= heltec_v4_motion
MESHCORE := MeshCore
MESHCORE_REPO ?= https://github.com/meshcore-dev/MeshCore.git
MESHCORE_REF ?= 0679dbeffc504d562d2f09eb072fdc223f8ffc2a
PIO ?= /home/a/.platformio/penv/bin/pio

.PHONY: setup build upload monitor clean test

setup:
	@if [ ! -d $(MESHCORE)/.git ]; then \
	  git clone $(MESHCORE_REPO) $(MESHCORE); \
	  git -C $(MESHCORE) fetch --depth 1 origin $(MESHCORE_REF); \
	  git -C $(MESHCORE) checkout --detach $(MESHCORE_REF); \
	fi
	ln -sfn ../extra/platformio.local.ini $(MESHCORE)/platformio.local.ini

build: setup
	cd $(MESHCORE) && $(PIO) run -e $(ENV)

upload: setup
	cd $(MESHCORE) && $(PIO) run -e $(ENV) -t upload

monitor:
	cd $(MESHCORE) && $(PIO) device monitor -b 115200

clean:
	cd $(MESHCORE) && $(PIO) run -e $(ENV) -t clean

test:
	python3 tools/test_wire.py
