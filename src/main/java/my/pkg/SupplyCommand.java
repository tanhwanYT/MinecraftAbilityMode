package my.pkg;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class SupplyCommand implements CommandExecutor, TabCompleter {

    private final SupplyManager supplyManager;

    public SupplyCommand(SupplyManager supplyManager) {
        this.supplyManager = supplyManager;
    }

    // /supply give <player> <itemId> [amount]
    // /supply list
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (args.length == 0) {
            sender.sendMessage("§e/supply list");
            sender.sendMessage("§e/supply give <player> <itemId> [amount]");
            return true;
        }

        if (args[0].equalsIgnoreCase("list")) {
            sender.sendMessage("§6[보급 아이템 목록]");
            sender.sendMessage("§f" + String.join("§7, §f", supplyManager.getAllItemIds()));
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (!sender.isOp()) {
                sender.sendMessage("§cOP only.");
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage("§c사용법: /supply give <player> <itemId> [amount]");
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§c플레이어를 찾을 수 없습니다: " + args[1]);
                return true;
            }

            String itemId = args[2];
            int amount = 1;
            if (args.length >= 4) {
                try {
                    amount = Math.max(1, Integer.parseInt(args[3]));
                } catch (NumberFormatException ignored) {
                    sender.sendMessage("§camount는 숫자여야 합니다.");
                    return true;
                }
            }

            ItemStack item = supplyManager.createItemById(itemId);
            if (item == null) {
                sender.sendMessage("§c존재하지 않는 itemId: " + itemId);
                sender.sendMessage("§e/supply list 로 목록 확인");
                return true;
            }

            // 커스텀 아이템은 대부분 스택 제한이 있어서, amount가 1 이상이면 여러 개로 나눠 지급
            int given = 0;
            while (given < amount) {
                ItemStack clone = item.clone();
                int stack = Math.min(clone.getMaxStackSize(), amount - given);
                clone.setAmount(stack);
                target.getInventory().addItem(clone);
                given += stack;
            }

            sender.sendMessage("§a지급 완료: §f" + target.getName() + " §7<= §e" + itemId + " x" + amount);
            target.sendMessage("§e[보급] 아이템이 지급되었습니다: §6" + itemId + " §fx" + amount);
            return true;
        }

        sender.sendMessage("§c알 수 없는 명령입니다. /supply");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("list", "give"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return filter(supplyManager.getAllItemIds(), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            return filter(List.of("1", "2", "3", "5", "10"), args[3]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> list, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : list) {
            if (s.toLowerCase(Locale.ROOT).startsWith(p)) out.add(s);
        }
        return out;
    }
}