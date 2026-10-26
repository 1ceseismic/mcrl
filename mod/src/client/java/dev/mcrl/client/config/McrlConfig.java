package dev.mcrl.client.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Locale;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

import dev.mcrl.match.MatchConfig;
import dev.mcrl.match.MatchSize;
import dev.mcrl.client.camera.CameraSettings;
import dev.mcrl.client.camera.CameraSettings.BallCamMode;
import dev.mcrl.client.fx.EffectsSettings;
import dev.mcrl.client.hud.HudSettings;
import dev.mcrl.client.input.InputMapper.MapperSettings;
import dev.mcrl.client.sound.ak.Mix;
import dev.mcrl.rlassets.bodies.BodyIds;

public final class McrlConfig {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static McrlConfig instance;

	public enum Visuals { RL, SIMPLE, HITBOX }

	public enum Team { BLUE, ORANGE }

	public enum TextureQuality { FULL, HALF }

	public record SoundSettings(float engine, float boost, float tires, float car, float ball, boolean dynamics) {
		public static final SoundSettings DEFAULT = new SoundSettings(1, 1, 1, 1, 1, true);

		public static String key(Mix.Group g) {
			return g.name().toLowerCase(Locale.ROOT);
		}

		public float level(Mix.Group g) {
			return switch (g) {
				case ENGINE -> engine;
				case BOOST -> boost;
				case TIRES -> tires;
				case CAR -> car;
				case BALL -> ball;
			};
		}

		public SoundSettings withLevel(Mix.Group g, float v) {
			return new SoundSettings(g == Mix.Group.ENGINE ? v : engine, g == Mix.Group.BOOST ? v : boost,
					g == Mix.Group.TIRES ? v : tires, g == Mix.Group.CAR ? v : car, g == Mix.Group.BALL ? v : ball,
					dynamics);
		}

		public SoundSettings withDynamics(boolean on) {
			return new SoundSettings(engine, boost, tires, car, ball, on);
		}
	}

	public CameraSettings camera = CameraSettings.RL_DEFAULTS;
	public boolean cameraMouseLook = true;
	public boolean cameraShake = true;
	public Visuals visuals = Visuals.RL;
	public Team team = Team.BLUE;
	public CarSettings car = CarSettings.DEFAULT;
	public TextureQuality textureQuality = TextureQuality.FULL;
	public boolean carShadow = true;
	public EffectsSettings effects = EffectsSettings.DEFAULTS;
	public MapperSettings controller = MapperSettings.DEFAULT;
	public boolean rumble = true;
	public HudSettings hud = HudSettings.DEFAULT;
	public String rlCookedDir = "";
	public String umodelPath = "";
	public String vgmstreamPath = "";
	public String rlCacheDir = "";
	public String arenaMeshesDir = "";
	public String botPolicy = "";
	public MatchSettings match = MatchSettings.DEFAULT;

	public record MatchSettings(MatchSize defaultSize, boolean farDetail, boolean bots, float botTemperature) {
		public static final MatchSettings DEFAULT = new MatchSettings(MatchSize.DUEL, true, true, 0f);

		public MatchSettings {
			botTemperature = MatchConfig.clampTemperature(botTemperature);
		}

		public MatchSettings withDefaultSize(MatchSize s) {
			return new MatchSettings(s, farDetail, bots, botTemperature);
		}

		public MatchSettings withFarDetail(boolean on) {
			return new MatchSettings(defaultSize, on, bots, botTemperature);
		}

		public MatchSettings withBots(boolean on) {
			return new MatchSettings(defaultSize, farDetail, on, botTemperature);
		}

		public MatchSettings withBotTemperature(float t) {
			return new MatchSettings(defaultSize, farDetail, bots, t);
		}
	}
	public record CarSettings(String body, int preset) {
		public static final CarSettings DEFAULT = new CarSettings("", 0);
	}

	public float soundVolume = 1f;
	public SoundSettings sound = SoundSettings.DEFAULT;
	private Mix.@Nullable Settings mix;
	private float mixVolume;
	private @Nullable SoundSettings mixSound;
	public boolean nativeEnabled = true;
	public boolean onlinePrediction = true;
	private Path file;

	public static synchronized McrlConfig get() {
		if (instance == null) {
			instance = load(FabricLoader.getInstance().getConfigDir().resolve("mcrl.json"));
		}
		return instance;
	}

	static McrlConfig load(Path file) {
		McrlConfig c = new McrlConfig();
		c.file = file;
		if (!Files.exists(file)) {
			return c;
		}
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(r);
			if (!root.isJsonObject()) {
				throw new IllegalStateException("not a JSON object");
			}
			JsonObject o = root.getAsJsonObject();
			if (o.has("camera")) {
				JsonObject cam = o.getAsJsonObject("camera");
				c.camera = readCamera(cam).clamp();
				c.cameraMouseLook = bool(cam, "mouseLook", true);
				c.cameraShake = bool(cam, "shake", true);
			}
			c.visuals = enumOr(o, "visuals", Visuals.class, Visuals.RL);
			c.team = enumOr(o, "team", Team.class, Team.BLUE);
			if (o.get("car") instanceof JsonObject co) {
				c.car = readCar(co);
			}
			c.textureQuality = enumOr(o, "textureQuality", TextureQuality.class, TextureQuality.FULL);
			c.carShadow = bool(o, "carShadow", true);
			JsonElement fx = o.get("effects");
			if (fx != null && fx.isJsonObject()) {
				c.effects = readEffects(fx.getAsJsonObject());
			}
			c.rlCookedDir = string(o, "rlCookedDir");
			c.umodelPath = string(o, "umodelPath");
			c.vgmstreamPath = string(o, "vgmstreamPath");
			c.rlCacheDir = string(o, "rlCacheDir");
			c.arenaMeshesDir = string(o, "arenaMeshesDir");
			c.botPolicy = string(o, "botPolicy");
			if (o.get("match") instanceof JsonObject mo) {
				MatchSize size = MatchSize.parse(string(mo, "defaultSize"));
				JsonElement t = mo.get("botTemperature");
				float temperature = t != null && t.isJsonPrimitive() && t.getAsJsonPrimitive().isNumber() ? t.getAsFloat()
						: 0f;
				c.match = new MatchSettings(size != null ? size : MatchSize.DUEL, bool(mo, "farDetail", true),
						bool(mo, "bots", true), temperature);
			}
			c.soundVolume = unitFloat(o, "soundVolume", 1f);
			if (o.get("sound") instanceof JsonObject so) {
				c.sound = readSound(so);
			}
			c.nativeEnabled = bool(o, "nativeEnabled", true);
			if (o.get("online") instanceof JsonObject on) {
				c.onlinePrediction = bool(on, "prediction", true);
			}
			if (o.has("hud")) {
				c.hud = readHud(o.getAsJsonObject("hud"));
			}
			if (o.has("controller")) {
				c.controller = readController(o.getAsJsonObject("controller"));
				c.rumble = bool(o.getAsJsonObject("controller"), "rumble", true);
			}
		} catch (IOException | RuntimeException e) {
			LOG.warn("mcrl: unreadable config {} ({}); using defaults", file, e.toString());
			try {
				Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e2) {
				LOG.warn("mcrl: could not rename the bad config: {}", e2.toString());
			}
			McrlConfig d = new McrlConfig();
			d.file = file;
			return d;
		}
		return c;
	}

	public static void edit(Consumer<McrlConfig> change) {
		McrlConfig c = get();
		change.accept(c);
		c.save();
	}

	public void save() {
		try {
			Path dir = file.toAbsolutePath().getParent();
			Files.createDirectories(dir);
			Path tmp = Files.createTempFile(dir, "mcrl", ".tmp");
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(toJson(), w);
			}
			try {
				Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			LOG.warn("mcrl: could not save the config {}: {}", file, e.toString());
		}
	}

	private JsonObject toJson() {
		JsonObject o = new JsonObject();
		JsonObject cam = new JsonObject();
		CameraSettings s = camera;
		cam.addProperty("fov", s.fov());
		cam.addProperty("distance", s.distance());
		cam.addProperty("height", s.height());
		cam.addProperty("angle", s.angle());
		cam.addProperty("stiffness", s.stiffness());
		cam.addProperty("swivelSpeed", s.swivelSpeed());
		cam.addProperty("transitionSpeed", s.transitionSpeed());
		cam.addProperty("invertSwivel", s.invertSwivel());
		cam.addProperty("ballCamMode", s.ballCamMode().name());
		cam.addProperty("mouseLook", cameraMouseLook);
		cam.addProperty("shake", cameraShake);
		o.add("camera", cam);
		o.addProperty("visuals", visuals.name());
		o.addProperty("team", team.name());
		JsonObject carJson = new JsonObject();
		carJson.addProperty("body", car.body());
		carJson.addProperty("preset", car.preset());
		o.add("car", carJson);
		o.addProperty("textureQuality", textureQuality.name());
		o.addProperty("carShadow", carShadow);
		JsonObject fx = new JsonObject();
		fx.addProperty("enabled", effects.enabled());
		fx.addProperty("boost", effects.boost());
		fx.addProperty("supersonic", effects.supersonic());
		fx.addProperty("wheels", effects.wheels());
		fx.addProperty("movement", effects.movement());
		fx.addProperty("impacts", effects.impacts());
		fx.addProperty("ball", effects.ball());
		fx.addProperty("spawn", effects.spawn());
		fx.addProperty("ownerOnly", effects.ownerOnly());
		fx.addProperty("quality", effects.quality().name());
		o.add("effects", fx);
		o.addProperty("rlCookedDir", rlCookedDir);
		o.addProperty("umodelPath", umodelPath);
		o.addProperty("vgmstreamPath", vgmstreamPath);
		o.addProperty("rlCacheDir", rlCacheDir);
		o.addProperty("arenaMeshesDir", arenaMeshesDir);
		o.addProperty("botPolicy", botPolicy);
		JsonObject matchJson = new JsonObject();
		matchJson.addProperty("defaultSize", match.defaultSize().name());
		matchJson.addProperty("farDetail", match.farDetail());
		matchJson.addProperty("bots", match.bots());
		matchJson.addProperty("botTemperature", match.botTemperature());
		o.add("match", matchJson);
		o.addProperty("soundVolume", soundVolume);
		JsonObject snd = new JsonObject();
		for (Mix.Group g : Mix.Group.values()) {
			snd.addProperty(SoundSettings.key(g), sound.level(g));
		}
		snd.addProperty("dynamics", sound.dynamics());
		o.add("sound", snd);
		o.addProperty("nativeEnabled", nativeEnabled);
		JsonObject online = new JsonObject();
		online.addProperty("prediction", onlinePrediction);
		o.add("online", online);
		JsonObject hudJson = new JsonObject();
		hudJson.addProperty("boostMeter", hud.boostMeter());
		hudJson.addProperty("ballCamIndicator", hud.ballCamIndicator());
		hudJson.addProperty("speed", hud.speed());
		hudJson.addProperty("hideVanilla", hud.hideVanilla());
		o.add("hud", hudJson);
		JsonObject ctl = new JsonObject();
		ctl.addProperty("stickDeadzone", controller.stickDeadzone());
		ctl.addProperty("triggerDeadzone", controller.triggerDeadzone());
		ctl.addProperty("sensitivity", controller.sensitivity());
		ctl.addProperty("rumble", rumble);
		o.add("controller", ctl);
		return o;
	}

	public Mix.Settings mixSettings() {
		Mix.Settings m = mix;
		if (m == null || mixVolume != soundVolume || mixSound != sound) {
			EnumMap<Mix.Group, Double> levels = new EnumMap<>(Mix.Group.class);
			for (Mix.Group g : Mix.Group.values()) {
				levels.put(g, (double) sound.level(g));
			}
			m = new Mix.Settings(sound.dynamics(), soundVolume, levels);
			mix = m;
			mixVolume = soundVolume;
			mixSound = sound;
		}
		return m;
	}

	private static CarSettings readCar(JsonObject o) {
		String body = string(o, "body");
		JsonElement p = o.get("preset");
		int preset = p != null && p.isJsonPrimitive() && p.getAsJsonPrimitive().isNumber() ? p.getAsInt() : 0;
		return new CarSettings(body.isEmpty() || BodyIds.valid(body) ? body : "", preset >= 0 && preset <= 6 ? preset : 0);
	}

	private static SoundSettings readSound(JsonObject o) {
		SoundSettings s = SoundSettings.DEFAULT;
		for (Mix.Group g : Mix.Group.values()) {
			s = s.withLevel(g, unitFloat(o, SoundSettings.key(g), 1f));
		}
		return s.withDynamics(bool(o, "dynamics", true));
	}

	private static CameraSettings readCamera(JsonObject o) {
		CameraSettings d = CameraSettings.RL_DEFAULTS;
		BallCamMode mode = d.ballCamMode();
		if (o.has("ballCamMode")) {
			try {
				mode = BallCamMode.valueOf(o.get("ballCamMode").getAsString());
			} catch (IllegalArgumentException ignored) {
			}
		}
		return new CameraSettings(f(o, "fov", d.fov()), f(o, "distance", d.distance()), f(o, "height", d.height()),
				f(o, "angle", d.angle()), f(o, "stiffness", d.stiffness()), f(o, "swivelSpeed", d.swivelSpeed()),
				f(o, "transitionSpeed", d.transitionSpeed()),
				o.has("invertSwivel") ? o.get("invertSwivel").getAsBoolean() : d.invertSwivel(), mode);
	}

	private static HudSettings readHud(JsonObject o) {
		HudSettings d = HudSettings.DEFAULT;
		return new HudSettings(bool(o, "boostMeter", d.boostMeter()), bool(o, "ballCamIndicator", d.ballCamIndicator()),
				bool(o, "speed", d.speed()), bool(o, "hideVanilla", d.hideVanilla()));
	}

	private static MapperSettings readController(JsonObject o) {
		MapperSettings d = MapperSettings.DEFAULT;
		float stick = clampTo(f(o, "stickDeadzone", d.stickDeadzone()), 0f, 0.95f, d.stickDeadzone());
		float trig = clampTo(f(o, "triggerDeadzone", d.triggerDeadzone()), 0f, 0.95f, d.triggerDeadzone());
		float sens = clampTo(f(o, "sensitivity", d.sensitivity()), 0.1f, 5f, d.sensitivity());
		return new MapperSettings(stick, trig, sens);
	}

	private static EffectsSettings readEffects(JsonObject o) {
		EffectsSettings d = EffectsSettings.DEFAULTS;
		return new EffectsSettings(bool(o, "enabled", d.enabled()), bool(o, "boost", d.boost()),
				bool(o, "supersonic", d.supersonic()), bool(o, "wheels", d.wheels()), bool(o, "movement", d.movement()),
				bool(o, "impacts", d.impacts()), bool(o, "ball", d.ball()), bool(o, "spawn", d.spawn()),
				bool(o, "ownerOnly", d.ownerOnly()), enumOr(o, "quality", EffectsSettings.Quality.class, d.quality()));
	}

	private static float clampTo(float v, float lo, float hi, float fallback) {
		return Float.isNaN(v) ? fallback : Math.max(lo, Math.min(hi, v));
	}

	private static <E extends Enum<E>> E enumOr(JsonObject o, String key, Class<E> type, E fallback) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
			return fallback;
		}
		try {
			return Enum.valueOf(type, e.getAsString().trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException unknown) {
			return fallback;
		}
	}

	private static String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : "";
	}

	private static boolean bool(JsonObject o, String key, boolean fallback) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : fallback;
	}

	private static float unitFloat(JsonObject o, String key, float fallback) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			return fallback;
		}
		return clampTo(e.getAsFloat(), 0f, 1f, fallback);
	}

	private static float f(JsonObject o, String key, float fallback) {
		return o.has(key) ? o.get(key).getAsFloat() : fallback;
	}
}
