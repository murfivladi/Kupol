package dev.evoday.gate.storage;

import java.util.UUID;

public record Account(UUID uuid, String name, String hash, String regIp, String lastIp, long lastLogin) {
}
