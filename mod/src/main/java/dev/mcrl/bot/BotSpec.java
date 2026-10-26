package dev.mcrl.bot;

public record BotSpec(BotPolicies.Loaded policy, float temperature, long seed) {
}
