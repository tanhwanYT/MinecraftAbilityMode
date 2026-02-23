package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BodyguardAbility implements Ability, Listener {

    private final JavaPlugin plugin;

    // 플레이어별 보호대상
    private static final Map<UUID, UUID> targetMap = new ConcurrentHashMap<>();

    // 최대체력 모디파이어(중복 방지용 고정 UUID)
    private final NamespacedKey HP_MOD_KEY;
    private static final String HP_MOD_NAME = "bodyguard_hp_bonus";

    private static boolean listenerRegistered = false;

    // 버프 값
    private static final double BONUS_HP = 6.0; // 3칸 = 3 hearts = 6 health
    private static final int SPEED_AMP = 0;     // Speed I
    private static final int LONG_TICKS = 20 * 60 * 60; // 1시간(사실상 지속)

    public BodyguardAbility(JavaPlugin plugin) {
        this.plugin = plugin;
        this.HP_MOD_KEY = new NamespacedKey(plugin, "bodyguard_hp_bonus");

        if (!listenerRegistered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            listenerRegistered = true;
        }
    }

    @Override
    public String id() { return "bodyguard"; }

    @Override
    public String name() { return "보디가드"; }

    @Override
    public int cooldownSeconds() { return 0; } // 패시브

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("보디가드 : 자신을 제외한 플레이어중 랜덤으로 한명이 보호대상이됩니다. 보호대상이 살아있을때 각종 버프를 받지만, 보호대상이 죽으면 버프가 사라집니다.");
        Player target = pickRandomOtherPlayer(player);
        if (target == null) {
            player.sendMessage("§7[보디가드] 현재 보호대상을 지정할 수 없습니다(다른 플레이어 없음).");
            targetMap.remove(player.getUniqueId());
            removeBuff(player);
            return;
        }

        targetMap.put(player.getUniqueId(), target.getUniqueId());
        player.sendMessage("§a[보디가드] 보호대상: §e" + target.getName());
        applyBuff(player, target);
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        targetMap.remove(player.getUniqueId());
        removeBuff(player);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        player.sendMessage("§7[보디가드] 패시브 능력입니다.");
        return false;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        UUID deadId = dead.getUniqueId();

        // 죽은 사람이 누군가의 보호대상인지 확인
        for (Map.Entry<UUID, UUID> e : new HashSet<>(targetMap.entrySet())) {
            UUID bodyguardId = e.getKey();
            UUID targetId = e.getValue();
            if (!deadId.equals(targetId)) continue;

            Player bodyguard = Bukkit.getPlayer(bodyguardId);
            if (bodyguard == null) continue;

            // 보호대상 죽음 -> 버프 제거
            bodyguard.sendMessage("§c[보디가드] 보호대상(§e" + dead.getName() + "§c)이 죽었습니다. 버프가 사라집니다.");
            removeBuff(bodyguard);
            // 대상은 유지(재지정 없음). 원하면 여기서 targetMap.remove(bodyguardId) 해도 됨.
        }
    }

    private Player pickRandomOtherPlayer(Player self) {
        List<Player> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.equals(self)) continue;
            if (p.isDead()) continue;
            list.add(p);
        }
        if (list.isEmpty()) return null;
        return list.get(new Random().nextInt(list.size()));
    }

    private void applyBuff(Player owner, Player target) {
        if (target == null || target.isDead() || !target.isOnline()) {
            removeBuff(owner);
            return;
        }

        var attr = owner.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            // ✅ 1.21+ : Key로 식별해서 제거
            attr.getModifiers().stream()
                    .filter(m -> HP_MOD_KEY.equals(m.getKey()))
                    .forEach(attr::removeModifier);

            // ✅ 1.21+ : NamespacedKey 기반 생성자 사용 (Spigot 호환 위해 slotGroup 포함)
            attr.addModifier(new AttributeModifier(
                    HP_MOD_KEY,
                    BONUS_HP,
                    AttributeModifier.Operation.ADD_NUMBER,
                    EquipmentSlotGroup.ANY
            ));

            double max = owner.getMaxHealth();
            if (owner.getHealth() > max) owner.setHealth(max);
        }

        owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, LONG_TICKS, SPEED_AMP, true, false, true));
    }

    private void removeBuff(Player owner) {
        var attr = owner.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            attr.getModifiers().stream()
                    .filter(m -> HP_MOD_KEY.equals(m.getKey()))
                    .forEach(attr::removeModifier);

            double max = owner.getMaxHealth();
            if (owner.getHealth() > max) owner.setHealth(max);
        }

        owner.removePotionEffect(PotionEffectType.SPEED);
    }
}