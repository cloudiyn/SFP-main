package cn.starfallplain.sfpmain.ui;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.Messages;
import cn.starfallplain.sfpmain.menu.MenuManager;
import cn.starfallplain.sfpmain.teleport.TeleportManager;
import cn.starfallplain.sfpmain.teleport.db.StoredLocation;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * dialogUI 版的家列表 / 传送点列表（两者共用一个实现，{@code home} 区分数据源）。
 * <p>
 * 一页显示 {@value #PAGE_SIZE} 个条目，底部「上一页 / 返回主菜单 / 下一页」按钮翻页
 * （点翻页 = 重开一个弹窗）。点击条目 = 传送，统一走
 * {@link TeleportManager#teleportHome} / {@link TeleportManager#teleportWarp}，
 * 与命令、箱子 UI 行为一致。
 * <p>
 * 家列表的条目点击后会再弹「传送 / 删除」选择框（dialogUI 按钮无左右键，
 * 用两步交互补上删除），传送点条目直接传送。
 * <p>
 * 全部文案来自 {@code messages.yml}（{@code dialogui.*} / 复用 {@code home.title}、
 * {@code warp.title}、{@code teleport.prev|next}、{@code common.back-to-menu}），
 * 与箱子 UI 共用同一批键，不存在写死在代码里的中文。
 */
public final class DialogTeleportList {

    private static final int PAGE_SIZE = 6;
    private static final Duration LIFETIME = Duration.ofMinutes(10);

    private DialogTeleportList() {
    }

    /**
     * @param home true = 家列表，false = 传送点列表
     * @param page 0-based 页码
     */
    public static void open(SfpMain plugin, Player player, boolean home, int page) {
        TeleportManager manager = plugin.getTeleportManager();
        if (manager == null) {
            player.sendMessage(plugin.getMessage("common.feature-disabled", "<red>该功能当前未启用。</red>"));
            return;
        }

        List<String> names = home
                ? manager.getHomeStore().listNames(player.getUniqueId())
                : manager.getWarpStore().listNames();

        if (names.isEmpty()) {
            player.sendMessage(plugin.getMessage(home ? "home.none" : "warp.none",
                    home ? "<yellow>你还没有设置任何家。</yellow>" : "<yellow>当前没有任何传送点。</yellow>"));
            return;
        }

        int totalPages = Math.max(1, (names.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        final int currentPage = Math.max(0, Math.min(page, totalPages - 1));

        int start = currentPage * PAGE_SIZE;
        List<String> pageNames = names.subList(start, Math.min(start + PAGE_SIZE, names.size()));

        ClickCallback.Options options = ClickCallback.Options.builder().uses(1).lifetime(LIFETIME).build();
        List<ActionButton> buttons = new ArrayList<>();

        for (String name : pageNames) {
            StoredLocation loc = home
                    ? manager.getHomeStore().get(player.getUniqueId(), name)
                    : manager.getWarpStore().get(name);
            Component label = msg(plugin, "dialogui.entry-name", "<white>{name}</white>",
                    Map.of("name", name));
            Component tooltip = loc != null
                    ? msg(plugin, "dialogui.entry-hover-location", "<gray>{location}</gray>",
                            Map.of("location", loc.describe()))
                    : null;

            final String targetName = name;
            ActionButton.Builder builder = ActionButton.builder(label);
            if (tooltip != null) {
                builder.tooltip(tooltip);
            }
            builder.action(DialogAction.customClick((view, audience) -> {
                if (audience instanceof Player p && p.isOnline()) {
                    if (home) {
                        openHomeActionDialog(plugin, p, manager, targetName, options);
                    } else {
                        manager.teleportWarp(p, targetName);
                    }
                }
            }, options));
            buttons.add(builder.build());
        }

        // 导航：上一页 / 返回主菜单 / 下一页
        if (currentPage > 0) {
            buttons.add(navButton(plugin, "teleport.prev", "<!i><yellow>上一页</yellow>",
                    player, home, currentPage - 1, options));
        }
        buttons.add(ActionButton.builder(plugin.getMessage("common.back-to-menu",
                        "<!i><yellow>返回主菜单</yellow>"))
                .action(DialogAction.customClick((view, audience) -> {
                    if (audience instanceof Player p && p.isOnline()) {
                        MenuManager.openMenuByPreference(plugin, p);
                    }
                }, options))
                .build());
        if (currentPage < totalPages - 1) {
            buttons.add(navButton(plugin, "teleport.next", "<!i><yellow>下一页</yellow>",
                    player, home, currentPage + 1, options));
        }

        // 标题复用箱子 UI 的 home.title / warp.title，保证两种界面标题一致
        Map<String, String> titlePh = new LinkedHashMap<>();
        titlePh.put("page", String.valueOf(currentPage + 1));
        titlePh.put("pages", String.valueOf(totalPages));
        titlePh.put("total", String.valueOf(names.size()));
        String titleKey = home ? "home.title" : "warp.title";
        String titleFallback = home
                ? "<!i><aqua>我的家</aqua> <gray>- 第 {page}/{pages} 页</gray>"
                : "<!i><light_purple>传送点</light_purple> <gray>- 第 {page}/{pages} 页</gray>";
        Component title = msg(plugin, titleKey, titleFallback, titlePh);

        Dialog dialog = Dialog.create(builder -> builder
                .empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons).columns(1).build()));

        player.showDialog(dialog);
    }

    /**
     * 点击家条目后弹出「传送 / 删除」选择框。
     * <p>
     * dialogUI 的按钮没有左右键之分，所以用两步交互补上「删除自己的家」，
     * 与箱子 UI 的「左键传送 / 右键删除」功能对等。
     */
    private static void openHomeActionDialog(SfpMain plugin, Player player,
                                             TeleportManager manager, String name,
                                             ClickCallback.Options options) {
        Map<String, String> ph = Map.of("name", name);
        Component title = msg(plugin, "dialogui.home-action-title", "<!i><aqua>我的家</aqua>", Map.of());
        Component body = msg(plugin, "dialogui.home-action-body",
                "<!i><yellow>{name}</yellow> <gray>—— 选择操作</gray>", ph);

        ActionButton teleport = ActionButton.builder(
                        msg(plugin, "dialogui.home-teleport", "<!i><green>传送</green>", Map.of()))
                .tooltip(msg(plugin, "dialogui.home-teleport-hover",
                        "<!i><gray>传送到这个家</gray>", Map.of()))
                .action(DialogAction.customClick((view, audience) -> {
                    if (audience instanceof Player p && p.isOnline()) {
                        manager.teleportHome(p, name);
                    }
                }, options))
                .build();
        ActionButton delete = ActionButton.builder(
                        msg(plugin, "dialogui.home-delete", "<!i><red>删除这个家</red>", Map.of()))
                .tooltip(msg(plugin, "dialogui.home-delete-hover",
                        "<!i><gray>等同 /delhome {name}</gray>", ph))
                .action(DialogAction.customClick((view, audience) -> {
                    if (audience instanceof Player p && p.isOnline()) {
                        manager.deleteHome(p, name);
                    }
                }, options))
                .build();

        Dialog dialog = Dialog.create(builder -> builder
                .empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(body)))
                        .build())
                .type(DialogType.confirmation(teleport, delete)));

        player.showDialog(dialog);
    }

    private static ActionButton navButton(SfpMain plugin, String msgKey, String fallback,
                                          Player player, boolean home, int page,
                                          ClickCallback.Options options) {
        return ActionButton.builder(plugin.getMessage(msgKey, fallback))
                .action(DialogAction.customClick((view, audience) -> {
                    if (audience instanceof Player p && p.isOnline()) {
                        open(plugin, p, home, page);
                    }
                }, options))
                .build();
    }

    /** 取 messages.yml 里的文案（带占位符），解析为组件 */
    private static Component msg(SfpMain plugin, String key, String fallback, Map<String, String> ph) {
        return Messages.deserialize(Messages.apply(plugin.getRawMessage(key, fallback), ph));
    }
}
