package my.pkg.item;

import my.pkg.SupplyItem;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.GameMode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class OldPunishmentPostcardItem implements SupplyItem {

    private final NamespacedKey itemIdKey;

    private static final int EFFECT_TICKS = 20 * 5; // 5초

    public OldPunishmentPostcardItem(NamespacedKey itemIdKey) {
        this.itemIdKey = itemIdKey;
    }

    @Override
    public String id() {
        return "old_punishment_postcard";
    }

    @Override
    public ItemStack create(JavaPlugin plugin) {
        ItemStack item = new ItemStack(Material.PAPER, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§8낡은 징벌의 엽서");
            meta.setLore(List.of(
                    "§7사용 시 랜덤 플레이어 1명에게",
                    "§7독과 구속을 5초 부여합니다."
            ));
            meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, id());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    @Override
    public void onRightClick(JavaPlugin plugin, Player player, PlayerInteractEvent event) {
        List<Player> candidates = new ArrayList<>();

        for (Player other : player.getWorld().getPlayers()) {
            if (other.equals(player)) continue;
            if (!other.isOnline() || other.isDead()) continue;
            if (other.getGameMode() == GameMode.SPECTATOR) continue; // 관전자 제외
            candidates.add(other);
        }

        if (candidates.isEmpty()) {
            player.sendMessage("§7[엽서] §f징벌할 플레이어가 없습니다.");
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 0.8f);
            event.setCancelled(true);
            return;
        }

        Player target = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));

        target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, EFFECT_TICKS, 0, true, true, true));
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, EFFECT_TICKS, 1, true, true, true));

        player.sendMessage("§8[엽서] §f" + target.getName() + " 에게 징벌을 내렸습니다.");
        target.sendMessage("§8[엽서] §f낡은 징벌의 엽서로 인해 명성이 떨어졌습니다.");

        player.playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.9f, 1.0f);
        target.playSound(target.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 0.8f, 0.7f);

        // 아이템 1개 소모
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(hand.getAmount() - 1);
            player.getInventory().setItemInMainHand(hand);
        }

        event.setCancelled(true);
    }
}