package dev.mcrl.rlassets.model;

import org.jspecify.annotations.Nullable;

public record SoundCue(String start, @Nullable String stop) {
}
