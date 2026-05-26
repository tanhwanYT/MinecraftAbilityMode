package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class WildCardAbility implements Ability {

    private static final Set<String> COMBAT = Set.of(
            "malphite", "viper", "antman", "shadowstep", "chainarm", "donation"
    );

    private static final Set<String> SURVIVAL = Set.of(
            "kiyathow", "panic", "glow"
    );

    private static final Set<String> MOVEMENT = Set.of(
            "taliyah", "backattacker"
    );

    private static final List<String> POOL = List.of(
            "malphite",
            "viper",
            "kiyathow",
            "antman",
            "sniper",
            "donation",
            "panic",
            "glow",
            "chainarm",
            "shadowstep",
            "taliyah",
            "backattacker"
    );

    private final Map<UUID, String> lastPicked = new HashMap<>();

    @Override
    public String id() {
        return "wildcard";
    }

    @Override
    public String name() {
        return "와일드카드";
    }

    @Override
    public int cooldownSeconds() {
        return 25;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("§a와일드카드 §7: 네더 스타 우클릭 시 상황에 맞는 액티브 능력 중 하나가 랜덤으로 발동됩니다");
        player.sendMessage("§7체력이 낮으면 생존/이동형, 주변에 적이 있으면 전투형 확률이 증가합니다.");
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        UUID uuid = player.getUniqueId();
        String prev = lastPicked.get(uuid);

        List<WeightedAbility> candidates = new ArrayList<>();

        boolean lowHealth = isLowHealth(player);
        boolean nearEnemy = hasNearbyEnemy(player, 8.0);

        for (String key : POOL) {
            Ability ability = system.getRegisteredAbility(key);
            if (ability == null) continue;
            if (ability == this) continue;
            if (prev != null && key.equalsIgnoreCase(prev)) continue;

            int weight = getWeight(key, lowHealth, nearEnemy);
            candidates.add(new WeightedAbility(key, ability, weight));
        }

        // 전부 걸러졌으면 연속 방지 무시
        if (candidates.isEmpty()) {
            for (String key : POOL) {
                Ability ability = system.getRegisteredAbility(key);
                if (ability == null) continue;
                if (ability == this) continue;

                int weight = getWeight(key, lowHealth, nearEnemy);
                candidates.add(new WeightedAbility(key, ability, weight));
            }
        }

        if (candidates.isEmpty()) {
            player.sendMessage("§c[와일드카드] 사용 가능한 후보 능력이 없습니다.");
            return false;
        }

        WeightedAbility picked = pickWeighted(candidates);
        Ability pickedAbility = picked.ability();

        lastPicked.put(uuid, picked.key().toLowerCase());

        player.sendMessage("§d[와일드카드] §f이번 스킬: §e" + pickedAbility.name());

        if (lowHealth) {
            player.sendMessage("§7체력이 낮아 §a생존형/이동형§7 확률이 증가했습니다.");
        } else if (nearEnemy) {
            player.sendMessage("§7근처에 적이 있어 §c전투형§7 확률이 증가했습니다.");
        }

        player.sendActionBar("§d와일드카드 → §e" + pickedAbility.name());
        player.playSound(player.getLocation(), Sound.UI_TOAST_IN, 1.0f, 1.2f);

        try {
            boolean activated = pickedAbility.activate(system, player);

            if (!activated) {
                player.sendMessage("§c[와일드카드] 뽑힌 능력이 지금은 발동되지 않았습니다.");
                return false;
            }

            return true;
        } catch (Exception e) {
            player.sendMessage("§c[와일드카드] 랜덤 스킬 발동 중 오류가 발생했습니다: " + pickedAbility.name());
            e.printStackTrace();
            return false;
        }
    }

    private int getWeight(String key, boolean lowHealth, boolean nearEnemy) {
        int weight = 10;

        if (lowHealth) {
            if (SURVIVAL.contains(key)) weight += 25;
            if (MOVEMENT.contains(key)) weight += 15;
            if (COMBAT.contains(key)) weight += 3;
        } else if (nearEnemy) {
            if (COMBAT.contains(key)) weight += 25;
            if (MOVEMENT.contains(key)) weight += 8;
            if (SURVIVAL.contains(key)) weight += 3;
        } else {
            if (MOVEMENT.contains(key)) weight += 10;
            if (SURVIVAL.contains(key)) weight += 6;
            if (COMBAT.contains(key)) weight += 4;
        }

        return weight;
    }

    private boolean isLowHealth(Player player) {
        AttributeInstance maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;

        return player.getHealth() <= maxHealth * 0.4;
    }

    private boolean hasNearbyEnemy(Player player, double range) {
        return player.getNearbyEntities(range, range, range).stream()
                .filter(entity -> entity instanceof Player)
                .map(entity -> (Player) entity)
                .anyMatch(other ->
                        !other.equals(player)
                                && other.isOnline()
                                && other.getGameMode() == GameMode.SURVIVAL
                                && !other.isDead()
                );
    }

    private WeightedAbility pickWeighted(List<WeightedAbility> list) {
        int totalWeight = 0;

        for (WeightedAbility ability : list) {
            totalWeight += ability.weight();
        }

        int random = ThreadLocalRandom.current().nextInt(totalWeight);

        for (WeightedAbility ability : list) {
            random -= ability.weight();
            if (random < 0) {
                return ability;
            }
        }

        return list.get(0);
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        lastPicked.remove(player.getUniqueId());
    }

    private record WeightedAbility(String key, Ability ability, int weight) {
    }
}