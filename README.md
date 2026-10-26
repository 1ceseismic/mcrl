# mcrl

rocket league in minecraft 26.3 (linux)
using [RocketSim](https://github.com/ZealanL/RocketSim) for physics sim

Supports ingame rl matches, supports trained gigalearn ML bots (included misc of mine for reference), or real players over LAN

Needs a rocket league install for inital assets retrieval

#### build

JDK: 25, Cmake: 3.20+, C++20

```
git clone --recursive https://github.com/1ceseismic/mcrl
cd mcrl
cmake -S native -B native/build
cmake --build native/build -j4
cd mod && ./gradlew build
```

Put `mod/build/libs/mcrl-0.1.0.jar` and Fabric api in your mc mods folder

#### assets

Build umodel and vgmstream, then find your install (you can set env var MCRL_RL_DIR to full path)
```
scripts/setup-tools.sh 
cd mod && ./gradlew :rlassets:run --args="convert --stadium --bodies all"
```

#### play
ingame mc chat commands:
```
/mcrl car     spawn car, right click to interact
/mcrl ball        spawn ball
/mcrl match create 2v2
/mcrl match join orange
/mcrl match start
```
more commands/options are in the mcrl settings (M)

Uses default rl controls (support both kbm and controller):
Bots need a GigaLearn policy, set on the Match tab.

### Licence

GPL-3.0-or-later

no data/assets from game are included
