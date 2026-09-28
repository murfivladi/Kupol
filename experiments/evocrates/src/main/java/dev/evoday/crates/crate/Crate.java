package dev.evoday.crates.crate;

import net.kyori.adventure.text.Component;

import java.security.SecureRandom;
import java.util.List;

public final class Crate {

    public enum Animation { ROULETTE, WORLD }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String id;
    private final Component name;
    private final Animation animation;
    private final List<Component> hologram;
    private final List<Reward> rewards;
    private final int totalWeight;

    public Crate(String id, Component name, Animation animation, List<Component> hologram, List<Reward> rewards) {
        this.id = id;
        this.name = name;
        this.animation = animation;
        this.hologram = hologram;
        this.rewards = rewards;
        this.totalWeight = rewards.stream().mapToInt(Reward::weight).sum();
    }

    public String id() {
        return id;
    }

    public Component name() {
        return name;
    }

    public Animation animation() {
        return animation;
    }

    public List<Component> hologram() {
        return hologram;
    }

    public List<Reward> rewards() {
        return rewards;
    }

    public double chance(Reward reward) {
        return totalWeight == 0 ? 0 : reward.weight() * 100.0 / totalWeight;
    }

    public Reward roll() {
        int r = RANDOM.nextInt(totalWeight);
        for (Reward reward : rewards) {
            r -= reward.weight();
            if (r < 0) {
                return reward;
            }
        }
        return rewards.get(rewards.size() - 1);
    }
}
