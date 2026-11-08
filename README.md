# mcrl

rocket league in minecraft 26.3 (linux)
using [RocketSim](https://github.com/ZealanL/RocketSim) for physics sim

Supports ingame rl matches, supports trained gigalearn ML bots (included misc of mine for reference: `bots/20b`), or real players over LAN

Needs a rocket league install for initial assets retrieval

<img width="49%" alt="bots-3v3-goal-scored" src="https://github.com/user-attachments/assets/06c4e859-b8dd-44f4-9b8b-21c7add49428" /> <img width="49%" alt="mobs-village-line-demo" src="https://github.com/user-attachments/assets/089155ea-ca7c-4841-85fa-e499ac9df3a1" /> <img width="49%" height="270" alt="aerial-lake" src="https://github.com/user-attachments/assets/27f047e3-cc6d-4cb0-b6cb-555ed9b6c9c0" />

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

Uses default rl controls (support both kbm and controller)
Bots need a GigaLearn policy, set on the Match tab.

### Licence

GPL-3.0-or-later

no data/assets from game are included
