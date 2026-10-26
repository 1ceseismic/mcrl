#include "bot.h"

#include <algorithm>
#include <cmath>

#include "soccar.h"
#include "world.h"

using namespace RocketSim;

namespace mcrl::bot {
namespace {

constexpr int GROUND_ACTIONS = 24;
constexpr int PLAYER_VALUES = 19;
constexpr int NEUTRAL_ROW = 8;
// GigaLearn DefaultObs normalisation.
constexpr float POS_COEF_X = 1.f / 4096, POS_COEF_Y = 1.f / 5120, POS_COEF_Z = 1.f / 2044;
constexpr float VEL_COEF = 1.f / 2300, ANG_VEL_COEF = 1.f / 5.5f, BOOST_COEF = 1.f / 100;

std::array<Action, ACTIONS> build_actions() {
    std::array<Action, ACTIONS> t{};
    int n = 0;
    const float tri[] = {-1, 0, 1}, bin[] = {0, 1};
    for (float throttle : tri)
        for (float steer : tri)
            for (float boost : bin)
                for (float handbrake : bin) {
                    if (boost == 1 && throttle != 1) continue;
                    t[n++] = {throttle, steer, 0, steer, 0, 0, boost, handbrake};
                }
    for (float pitch : tri)
        for (float yaw : tri)
            for (float roll : tri)
                for (float jump : bin)
                    for (float boost : bin) {
                        if (jump == 1 && yaw != 0) continue;
                        if (pitch == 0 && roll == 0 && jump == 0) continue;
                        const float handbrake = (jump == 1 && (pitch != 0 || yaw != 0 || roll != 0)) ? 1.f : 0.f;
                        t[n++] = {boost, yaw, pitch, yaw, roll, jump, boost, handbrake};
                    }
    return t;
}

struct Masks {
    std::array<uint8_t, ACTIONS> ground{}, air{}, jump{}, boost{};
    Masks() {
        const auto& t = actions();
        for (int i = 0; i < ACTIONS; i++) {
            const Action& a = t[i];
            jump[i] = a.jump != 0;
            boost[i] = a.boost != 0;
            ground[i] = i < GROUND_ACTIONS;
            air[i] = i > GROUND_ACTIONS && a.jump == 0;  // as GigaLearn: row 24 is never an air row
            if (i < GROUND_ACTIONS && a.throttle == a.boost && (a.yaw != 0) == (a.handbrake != 0)) air[i] = 1;
        }
    }
};

const Masks& masks() {
    static const Masks m;
    return m;
}

void mask(const CarState& cs, uint8_t out[ACTIONS]) {
    const Masks& m = masks();
    for (int i = 0; i < ACTIONS; i++) out[i] = cs.isOnGround ? m.ground[i] : m.air[i];
    if (cs.boost == 0)
        for (int i = 0; i < ACTIONS; i++)
            if (m.boost[i]) out[i] = 0;
    const bool turtled = cs.worldContact.hasContact && cs.worldContact.contactNormal.z > 0.9f;
    if (cs.HasFlipOrJump() || turtled)
        for (int i = 0; i < ACTIONS; i++)
            if (m.jump[i]) out[i] = 1;
}

Vec inverted(const Vec& v, bool inv) { return inv ? Vec(-v.x, -v.y, v.z) : v; }

void put(float*& o, const Vec& v, float cx, float cy, float cz) {
    *o++ = v.x * cx;
    *o++ = v.y * cy;
    *o++ = v.z * cz;
}

void put_player(float*& o, const CarState& s, bool inv) {
    put(o, inverted(s.pos, inv), POS_COEF_X, POS_COEF_Y, POS_COEF_Z);
    put(o, inverted(s.rotMat.forward, inv), 1, 1, 1);
    put(o, inverted(s.rotMat.up, inv), 1, 1, 1);
    put(o, inverted(s.vel, inv), VEL_COEF, VEL_COEF, VEL_COEF);
    put(o, inverted(s.angVel, inv), ANG_VEL_COEF, ANG_VEL_COEF, ANG_VEL_COEF);
    *o++ = s.boost * BOOST_COEF;
    *o++ = s.isOnGround ? 1.f : 0.f;
    *o++ = s.HasFlipOrJump() ? 1.f : 0.f;
    *o++ = s.isDemoed ? 1.f : 0.f;
}

void put_ghost(float*& o) {
    const float g[PLAYER_VALUES] = {0, 4608 * POS_COEF_Y, 17 * POS_COEF_Z, 0, -1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0,
                                    0, 1, 1, 1};
    for (float v : g) *o++ = v;
}

int32_t opponent(McrlWorld& w, int32_t carId) {
    const Car* self = w.cars.at(carId);
    const Vec ball = w.arena->ball->GetState().pos;
    int32_t best = -1;
    bool bestLive = false;
    float bestDist = 0;
    for (const auto& [id, car] : w.cars) {
        if (car->team == self->team) continue;
        const CarState s = car->GetState();
        const bool live = !s.isDemoed;
        const float d = s.pos.DistSq(ball);
        const bool better = best < 0 || (live && !bestLive) ||
                            (live == bestLive && (d < bestDist || (d == bestDist && id < best)));
        if (better) {
            best = id;
            bestLive = live;
            bestDist = d;
        }
    }
    return best;
}

void observe(McrlWorld& w, int32_t carId, int32_t prevAction, float out[OBS]) {
    Car* self = w.cars.at(carId);
    const bool inv = self->team == Team::ORANGE;
    float* o = out;
    const BallState ball = w.arena->ball->GetState();
    put(o, inverted(ball.pos, inv), POS_COEF_X, POS_COEF_Y, POS_COEF_Z);
    put(o, inverted(ball.vel, inv), VEL_COEF, VEL_COEF, VEL_COEF);
    put(o, inverted(ball.angVel, inv), ANG_VEL_COEF, ANG_VEL_COEF, ANG_VEL_COEF);
    if (prevAction >= 0) {
        const Action& a = actions()[prevAction];
        const float v[8] = {a.throttle, a.steer, a.pitch, a.yaw, a.roll, a.jump, a.boost, a.handbrake};
        for (float x : v) *o++ = x;
    } else {
        for (int k = 0; k < 8; k++) *o++ = 0;
    }
    const auto& pads = w.arena->GetBoostPads();
    for (int i = 0; i < PADS; i++) *o++ = pads[OBS_PAD_TO_SOCCAR[inv ? PADS - 1 - i : i]]->GetState().isActive ? 1.f : 0.f;
    put_player(o, self->GetState(), inv);
    const int32_t opp = opponent(w, carId);
    if (opp >= 0) {
        put_player(o, w.cars.at(opp)->GetState(), inv);
    } else {
        put_ghost(o);
    }
}

uint64_t rng_seed(uint64_t seed) {
    uint64_t z = seed + 0x9E3779B97F4A7C15ull;  // splitmix64
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
    z ^= z >> 31;
    return z != 0 ? z : 1;
}

uint64_t next(uint64_t& x) {  // xorshift64*
    x ^= x >> 12;
    x ^= x << 25;
    x ^= x >> 27;
    return x * 0x2545F4914F6CDD1Dull;
}

int choose(const float* logits, const uint8_t* allowed, float temperature, uint64_t& rng) {
    int best = -1;
    for (int i = 0; i < ACTIONS; i++)
        if (allowed[i] && std::isfinite(logits[i]) && (best < 0 || logits[i] > logits[best])) best = i;
    if (best < 0) return NEUTRAL_ROW;
    if (temperature == 0) return best;
    const double m = logits[best];
    double w[ACTIONS], sum = 0;
    for (int i = 0; i < ACTIONS; i++) {
        w[i] = allowed[i] && std::isfinite(logits[i]) ? std::exp((logits[i] - m) / temperature) : 0;
        sum += w[i];
    }
    const double u = (double)(next(rng) >> 11) * 0x1.0p-53;
    const double target = u * sum;
    double run = 0;
    int last = best;
    for (int i = 0; i < ACTIONS; i++) {
        if (w[i] == 0) continue;
        run += w[i];
        last = i;
        if (run > target) return i;
    }
    return last;
}

CarControls controls_of(int32_t row) {
    CarControls c;
    if (row < 0) return c;
    const Action& a = actions()[row];
    c.throttle = a.throttle;
    c.steer = a.steer;
    c.pitch = a.pitch;
    c.yaw = a.yaw;
    c.roll = a.roll;
    c.jump = a.jump == 1;
    c.boost = a.boost == 1;
    c.handbrake = a.handbrake == 1;
    return c;
}

}

const std::array<Action, ACTIONS>& actions() {
    static const std::array<Action, ACTIONS> t = build_actions();
    return t;
}

void Policy::forward(const float* obs, float* logits, float* h0, float* h1) const {
    const float* x = obs;
    const size_t layers = b.size();
    for (size_t l = 0; l < layers; l++) {
        const int32_t in = dims[l], out = dims[l + 1];
        float* y = l + 1 == layers ? logits : (l % 2 == 0 ? h0 : h1);
        const float* bias = b[l].data();
        for (int32_t j = 0; j < out; j++) y[j] = bias[j];
        const float* wt = this->wt[l].data();
        for (int32_t i = 0; i < in; i++) {
            const float xi = x[i];
            if (xi == 0) continue;
            const float* row = wt + (size_t)i * out;
            for (int32_t j = 0; j < out; j++) y[j] += xi * row[j];
        }
        if (l + 1 < layers)
            for (int32_t j = 0; j < out; j++) y[j] = y[j] > 0 ? y[j] : 0;
        x = y;
    }
}

void before_tick(McrlWorld& w) {
    if (!w.bots) return;
    Bots& all = *w.bots;
    const int32_t lock = w.soccar->lockTicks;
    for (auto& [id, bot] : all.bots) {
        Car* car = w.cars.at(id);
        BotState& st = bot.st;
        if (car->_internalState.isDemoed || lock > 7) {
            st.held = true;
            st.phase = 0;
            st.current = st.pending = -1;
            car->controls = CarControls();
            continue;
        }
        st.held = false;
        if (st.phase == 0) {
            observe(w, id, st.current, all.obs);
            bot.policy->forward(all.obs, all.logits, all.h0.data(), all.h1.data());
            mask(car->GetState(), all.allowed);
            st.pending = choose(all.logits, all.allowed, bot.temperature, bot.rng);
            st.decisions++;
        }
        if (st.phase == 7) st.current = st.pending;
        car->controls = controls_of(st.current);
        st.phase = (st.phase + 1) % 8;
    }
}

int32_t add(McrlWorld& w, int32_t carId, const McrlBotPolicy* p, float temperature, uint64_t seed) {
    static const char* fn = "mcrl_bot_add";
    if (!w.soccar) return fail(MCRL_ERR_ARG, fn, "not a SOCCAR world");
    if (!p) return fail(MCRL_ERR_ARG, fn, "policy is NULL");
    if (!std::isfinite(temperature) || temperature < 0)
        return fail(MCRL_ERR_ARG, fn, "temperature must be finite and >= 0");
    if (w.cars.find(carId) == w.cars.end()) return fail(MCRL_ERR_NOT_FOUND, fn, "unknown car id");
    if (!w.bots) w.bots = std::make_unique<Bots>();
    Bots& all = *w.bots;
    if (all.bots.count(carId)) return fail(MCRL_ERR_ARG, fn, "the car is a bot already");
    if ((int32_t)all.h0.size() < p->p->maxWidth) {
        all.h0.resize(p->p->maxWidth);
        all.h1.resize(p->p->maxWidth);
    }
    Bot b;
    b.policy = p->p;
    b.temperature = temperature;
    b.rng = rng_seed(seed);
    all.bots.emplace(carId, std::move(b));
    return MCRL_OK;
}

int32_t remove(McrlWorld& w, int32_t carId) {
    if (w.bots) w.bots->bots.erase(carId);
    return MCRL_OK;
}

McrlBotPolicy* policy_create(const int32_t* dims, int32_t nLayers, const float* const* params) {
    auto bad = [](const char* what) -> McrlBotPolicy* {
        fail(MCRL_ERR_ARG, "mcrl_bot_policy_create", what);
        return nullptr;
    };
    if (!dims || !params) return bad("dims or params is NULL");
    if (nLayers < 1) return bad("nLayers must be >= 1");
    for (int32_t l = 0; l <= nLayers; l++)
        if (dims[l] < 1) return bad("every dim must be >= 1");
    if (dims[0] != OBS) return bad("dims[0] must be 89 (the observation)");
    if (dims[nLayers] != ACTIONS) return bad("dims[nLayers] must be 90 (the action rows)");
    auto pol = std::make_shared<Policy>();
    pol->dims.assign(dims, dims + nLayers + 1);
    for (int32_t l = 0; l < nLayers; l++) {
        const int32_t in = dims[l], out = dims[l + 1];
        const float* W = params[2 * l];
        const float* B = params[2 * l + 1];
        if (!W || !B) return bad("a layer's weights or bias is NULL");
        std::vector<float> wt((size_t)in * out), b(B, B + out);
        for (int32_t o = 0; o < out; o++)
            for (int32_t i = 0; i < in; i++) {
                const float v = W[(size_t)o * in + i];
                if (!std::isfinite(v)) return bad("a weight is not finite");
                wt[(size_t)i * out + o] = v;
            }
        for (float v : b)
            if (!std::isfinite(v)) return bad("a bias is not finite");
        pol->wt.push_back(std::move(wt));
        pol->b.push_back(std::move(b));
        if (l + 1 < nLayers) pol->maxWidth = std::max(pol->maxWidth, out);
    }
    pol->maxWidth = std::max<int32_t>(pol->maxWidth, 1);
    return new McrlBotPolicy{std::move(pol)};
}

int32_t policy_forward(const McrlBotPolicy* p, const float* obs, float* logits) {
    if (!p || !obs || !logits) return fail(MCRL_ERR_ARG, "mcrl_bot_policy_forward", "an argument is NULL");
    std::vector<float> h0(p->p->maxWidth), h1(p->p->maxWidth);
    p->p->forward(obs, logits, h0.data(), h1.data());
    return MCRL_OK;
}

int32_t car_get_controls(McrlWorld& w, int32_t carId, McrlControls* out) {
    static const char* fn = "mcrl_car_get_controls";
    if (!out) return fail(MCRL_ERR_ARG, fn, "out is NULL");
    auto it = w.cars.find(carId);
    if (it == w.cars.end()) return fail(MCRL_ERR_NOT_FOUND, fn, "unknown car id");
    const CarControls& c = it->second->_internalState.lastControls;
    McrlControls o{};
    o.throttle = c.throttle;
    o.steer = c.steer;
    o.pitch = c.pitch;
    o.yaw = c.yaw;
    o.roll = c.roll;
    o.jump = c.jump ? 1 : 0;
    o.boost = c.boost ? 1 : 0;
    o.handbrake = c.handbrake ? 1 : 0;
    *out = o;
    return MCRL_OK;
}

int32_t observe_checked(McrlWorld& w, int32_t carId, int32_t prevAction, float* out) {
    static const char* fn = "mcrl_bot_observe";
    if (!w.soccar) return fail(MCRL_ERR_ARG, fn, "not a SOCCAR world");
    if (prevAction < -1 || prevAction >= ACTIONS) return fail(MCRL_ERR_ARG, fn, "prevAction must be -1..89");
    if (!out) return fail(MCRL_ERR_ARG, fn, "out is NULL");
    if (w.cars.find(carId) == w.cars.end()) return fail(MCRL_ERR_NOT_FOUND, fn, "unknown car id");
    observe(w, carId, prevAction, out);
    return MCRL_OK;
}

}
