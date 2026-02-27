package my.pkg.item;

import my.pkg.SupplyItem;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class FireTicketItem implements SupplyItem {

    private final NamespacedKey itemIdKey;

    public FireTicketItem(NamespacedKey itemIdKey) {
        this.itemIdKey = itemIdKey;
    }

    @Override
    public String id() {
        return "fire_ticket";
    }

    @Override
    public ItemStack create(JavaPlugin plugin) {
        ItemStack it = new ItemStack(Material.PAPER, 1);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName("§6발화 인챈트권");
        meta.setLore(List.of(
                "§7우클릭: 소지 중인 철검 1개에",
                "§c발화 I§7 을 부여합니다"
        ));
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, id());
        it.setItemMeta(meta);
        return it;
    }

    @Override
    public void onRightClick(JavaPlugin plugin, Player p, PlayerInteractEvent e) {
        // 적용 대상 철검 찾기: (1) 메인핸드가 철검이면 우선, 아니면 인벤에서 첫 철검
        ItemStack main = p.getInventory().getItemInMainHand();
        ItemStack targetSword = null;

        if (main != null && main.getType() == Material.IRON_SWORD) {
            targetSword = main;
        } else {
            for (ItemStack it : p.getInventory().getContents()) {
                if (it != null && it.getType() == Material.IRON_SWORD) {
                    targetSword = it;
                    break;
                }
            }
        }

        if (targetSword == null) {
            p.sendMessage("§c[인챈트권] 철검이 없습니다!");
            return;
        }

        targetSword.addEnchantment(Enchantment.FIRE_ASPECT, 1);
        p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.2f);
        p.sendMessage("§a[인챈트권] 철검에 §c발화 I§a 을 부여했습니다!");

        consumeOne(e);
        e.setCancelled(true);
    }

    private void consumeOne(PlayerInteractEvent e) {
        ItemStack hand = e.getItem();
        if (hand == null) return;
        int amt = hand.getAmount();
        if (amt <= 1) hand.setAmount(0);
        else hand.setAmount(amt - 1);
    }
}