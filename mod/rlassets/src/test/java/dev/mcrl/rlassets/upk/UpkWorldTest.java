package dev.mcrl.rlassets.upk;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpkWorldTest {
	@TempDir
	Path tmp;

	static UpkFixtureWriter core() {
		UpkFixtureWriter w = new UpkFixtureWriter("Core");
		int thing = w.scriptClass("Thing", 0);
		int mode = w.scriptEnum(thing, "EMode", "A", "B");
		int vector = w.scriptStruct(thing, "Vector", 0, 0x80, w.body());
		for (String a : List.of("X", "Y", "Z")) {
			w.property(vector, a, "FloatProperty", 1, 0);
		}
		int item = w.scriptStruct(thing, "Item", 0, 0, w.body().floatProp("X", 1));
		w.property(item, "X", "FloatProperty", 1, 0);
		w.property(item, "Y", "FloatProperty", 1, 0);
		w.property(thing, "Speed", "FloatProperty", 1, 0);
		w.property(thing, "Count", "IntProperty", 1, 0);
		w.property(thing, "bOn", "BoolProperty", 1, 0);
		w.property(thing, "Tag", "NameProperty", 1, 0);
		w.property(thing, "Mode", "ByteProperty", 1, mode);
		w.property(thing, "Ref", "ObjectProperty", 1, thing);
		w.function(thing, "Tick");
		w.arrayProperty(thing, "Items", "StructProperty", item);
		w.property(thing, "Loc", "StructProperty", 1, vector);
		w.property(thing, "Label", "StrProperty", 1, 0);
		w.property(thing, "Arr", "IntProperty", 3, 0);
		w.property(thing, "Pick", "StructProperty", 1, item);
		w.arrayProperty(thing, "Modes", "ByteProperty", mode);
		w.arrayProperty(thing, "Bytes", "ByteProperty", 0);
		w.cdo(thing, w.body().floatProp("Speed", 1).strProp("Label", "hi"));
		return w;
	}

	static final class Data {
		final UpkFixtureWriter w;
		final int thing;

		Data(String name) {
			w = new UpkFixtureWriter(name);
			int core = w.imp("Core", "Package", 0, "Core");
			thing = w.imp("Core", "Class", core, "Thing");
		}

		UpkFixtureWriter.Body tags() {
			return w.body();
		}

		int obj(String name, int outer, UpkFixtureWriter.Body tags) {
			return w.exp(thing, outer, name, w.body().i32(0).raw(tags.toArray()).none().toArray());
		}

		int obj(String name, UpkFixtureWriter.Body tags) {
			return obj(name, 0, tags);
		}
	}

	UpkWorld world(Data... data) throws IOException {
		core().write(tmp);
		for (Data d : data) {
			d.w.write(tmp);
		}
		return new UpkWorld(tmp);
	}

	static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put((String) kv[i], kv[i + 1]);
		}
		return m;
	}

	@Test
	void tagged_struct_over_default() throws IOException {
		Data d = new Data("Data");
		int set = d.obj("Set", d.tags().structProp("Pick", "Item", d.tags().floatProp("Y", 4).none()));
		int unset = d.obj("Unset", d.tags());
		int arch = d.obj("Arch", d.tags().structProp("Pick", "Item", d.tags().floatProp("X", 5).none()));
		int inst = d.obj("Inst", d.tags().structProp("Pick", "Item", d.tags().floatProp("Y", 6).none()));
		d.w.export(inst).archetype = arch;
		UpkWorld w = world(d);
		assertEquals(map("X", 1f, "Y", 4f), w.obj("Data", set).props().get("Pick"));
		Map<String, Object> u = w.obj("Data", unset).props();
		assertEquals(map("X", 1f), u.get("Pick"));
		assertFalse(u.containsKey("Loc"));
		assertEquals(map("X", 5f, "Y", 6f), w.obj("Data", inst).props().get("Pick"));
		assertEquals(map("X", 1f), w.structDefault("Core.Thing.Item"));
		assertEquals(List.of(), w.warnings());
	}

	@Test
	void unknown_property_kept_with_warning() throws IOException {
		Data d = new Data("Data");
		int o = d.obj("Obj", d.tags().arrayProp("Mystery", 2, d.tags().i32(5).i32(6))
				.prop("Odd", "MapProperty", new byte[] {1, 2, 3})
				.structProp("Blob", "NoSuchStruct", d.tags().i32(-1))
				.floatProp("Speed", 3));
		UpkWorld w = world(d);
		Map<String, Object> p = w.obj("Data", o).props();
		assertEquals(new UpkUnknown("ArrayProperty", "020000000500000006000000"), p.get("Mystery"));
		assertEquals(new UpkUnknown("MapProperty", "010203"), p.get("Odd"));
		assertEquals(new UpkUnknown("StructProperty", "ffffffff"), p.get("Blob"));
		assertEquals(3f, p.get("Speed"));
		assertEquals(3, w.warnings().size(), w.warnings().toString());
		assertTrue(w.warnings().get(0).contains("Mystery"), w.warnings().toString());
		assertTrue(w.warnings().get(1).contains("Odd"), w.warnings().toString());
		assertTrue(w.warnings().get(2).contains("Blob") && w.warnings().get(2).contains("NoSuchStruct"),
				w.warnings().toString());
	}
}
