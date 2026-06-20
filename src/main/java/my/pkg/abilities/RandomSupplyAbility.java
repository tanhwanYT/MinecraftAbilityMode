package my.pkg.abilities;

import my.pkg.AbilitySystem;
import my.pkg.SupplyManager;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class RandomSupplyAbility implements Ability {

    private final SupplyManager supplyManager;

    private static final int COOLDOWN = 42;

    // 플레이어별 이미 나온 진짜 보급템
    private final Map<UUID, Set<String>> usedSupplyItems = new HashMap<>();

    // 플레이어별 이미 나온 가짜 보급템
    private final Map<UUID, Set<String>> usedTrashItems = new HashMap<>();

    public RandomSupplyAbility(SupplyManager supplyManager) {
        this.supplyManager = supplyManager;
    }

    @Override
    public String id() {
        return "randomsupply";
    }

    @Override
    public String name() {
        return "럭키박스";
    }

    @Override
    public int cooldownSeconds() {
        return COOLDOWN;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("§e럭키박스 §7: 능력 사용 시 50% 확률로 보급템을 얻습니다.");
        player.sendMessage("§7나머지 50% 확률로 쓸모없는 아이템을 얻습니다.");
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        UUID uuid = player.getUniqueId();
        usedSupplyItems.remove(uuid);
        usedTrashItems.remove(uuid);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        boolean realSupply = ThreadLocalRandom.current().nextBoolean();

        if (realSupply) {
            giveRandomSupplyItem(player);
        } else {
            giveRandomTrashItem(player);
        }

        return true;
    }

    private void giveRandomSupplyItem(Player player) {
        UUID uuid = player.getUniqueId();

        List<String> ids = new ArrayList<>(supplyManager.getAllItemIds());
        Set<String> used = usedSupplyItems.computeIfAbsent(uuid, k -> new HashSet<>());

        ids.removeIf(used::contains);

        if (ids.isEmpty()) {
            player.sendMessage("§c[럭키박스] 더 이상 새로운 진짜 보급템이 없습니다.");
            return;
        }

        String id = ids.get(ThreadLocalRandom.current().nextInt(ids.size()));
        ItemStack item = supplyManager.createItemById(id);

        if (item == null) {
            player.sendMessage("§c[럭키박스] 보급템 생성에 실패했습니다.");
            return;
        }

        used.add(id);
        giveItem(player, item);

        sendUnifiedMessage(player, item);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        player.getWorld().spawnParticle(
                Particle.HAPPY_VILLAGER,
                player.getLocation().add(0, 1.0, 0),
                20,
                0.4, 0.5, 0.4,
                0.05
        );
    }

    private void giveRandomTrashItem(Player player) {
        UUID uuid = player.getUniqueId();

        List<TrashEntry> trashItems = new ArrayList<>(getTrashItems());
        Set<String> used = usedTrashItems.computeIfAbsent(uuid, k -> new HashSet<>());

        trashItems.removeIf(trash -> used.contains(trash.id()));

        if (trashItems.isEmpty()) {
            player.sendMessage("§c[럭키박스] 더 이상 새로운 가짜 보급템이 없습니다.");
            return;
        }

        TrashEntry picked = trashItems.get(ThreadLocalRandom.current().nextInt(trashItems.size()));
        ItemStack item = picked.item();

        used.add(picked.id());
        giveItem(player, item);

        sendUnifiedMessage(player, item);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1.0f);
        player.getWorld().spawnParticle(
                Particle.SMOKE,
                player.getLocation().add(0, 1.0, 0),
                20,
                0.4, 0.5, 0.4,
                0.03
        );
    }

    private void sendUnifiedMessage(Player player, ItemStack item) {
        String itemName = "알 수 없는 아이템";

        if (item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            itemName = item.getItemMeta().getDisplayName();
        }

        player.sendMessage("§e[럭키박스] §f아이템을 획득했습니다: " + itemName);
    }

    private List<TrashEntry> getTrashItems() {
        return List.of(
                trash("excalibur_handle",
                        Material.STICK,
                        "§f엑스칼리버의 칼자루",
                        "§7내구성 X",
                        "§7날카로움 X 지만 칼자루다."),

                trash("premium_dirt",
                        Material.DIRT,
                        "§6프리미엄 흙",
                        "§7평범한 흙보다 비싸 보인다."),

                trash("carmilla",
                        Material.WITHER_ROSE,
                        "§f카르밀라",
                        "§7집중 전투라서",
                        "§8사용할 수 없다."),

                trash("fire_resistance_resistance_ring",
                        Material.WITHER_ROSE,
                        "§f화염 저항 저항의 반지",
                        "§7지니고 있을시 화염저항 버프를",
                        "§8받지 않는다."),

                trash("jeonseorae_sword",
                        Material.IRON_SWORD,
                        "§f전서래검",
                        "§7잘 다루면 압도적인 대미지를 내지만",
                        "§8당신은 잘 못 다뤄서 그냥 철검이다."),

                trash("poop",
                        Material.BROWN_DYE,
                        "§f똥",
                        "§7누군가 생각나는 아이템이다"),

                trash("hero_hate_comic",
                        Material.BOOK,
                        "§f이런 영웅은 싫어 만화책",
                        "§7개발자가 좋아하는 만화책"),

                trash("white_crow_feather",
                        Material.FEATHER,
                        "§f까마귀의 깃털",
                        "§7검정색이 아니라 하얀색이다.")
        );
    }

    private TrashEntry trash(String id, Material material, String name, String... lore) {
        return new TrashEntry(id, trashItem(material, name, lore));
    }

    private ItemStack trashItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }

        return item;
    }

    private void giveItem(Player player, ItemStack item) {
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);

        if (!leftover.isEmpty()) {
            leftover.values().forEach(left ->
                    player.getWorld().dropItemNaturally(player.getLocation(), left)
            );
        }
    }

    private record TrashEntry(String id, ItemStack item) {
    }
}