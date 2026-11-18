package at.esoren.vystormboards;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderContext;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderNamespace;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import org.bukkit.plugin.Plugin;

/**
 * The values of Vystorm Boards in Core's placeholder registry, namespace {@code boards}. Polls, guestbooks and rankings
 * live in plain maps, so every value is answered on the server thread only. A value whose feature is switched off is
 * empty. Guestbook texts are player input and never marked as formatted.
 *
 * <pre>
 * %vystorm_boards_votes_event%             {boards:votes:event}              all votes of poll "event"
 * %vystorm_boards_votes_event_pvp%         {boards:votes:event:pvp}          votes for option "pvp"
 * %vystorm_boards_votes_percent_event_pvp% {boards:votes_percent:event:pvp}  share of "pvp" in percent
 * %vystorm_boards_vote_event%              {boards:vote:event}               the viewer's option, or -
 * %vystorm_boards_poll_open_event%         {boards:poll_open:event}          true or false
 * %vystorm_boards_guestbook_count_lobby%   {boards:guestbook_count:lobby}    entries of a guestbook
 * %vystorm_boards_guestbook_name_lobby_1%  {boards:guestbook_name:lobby:1}   writer of the newest entry
 * %vystorm_boards_guestbook_text_lobby_1%  {boards:guestbook_text:lobby:1}   text of the newest entry
 * %vystorm_boards_rank_play_one_minute%    {boards:rank:play_one_minute}     the viewer's place in a ranking
 * %vystorm_boards_rank_value_play_one_minute%                                the viewer's value there
 * %vystorm_boards_top_name_play_one_minute_1% / top_value_...                name and value on a place
 * %vystorm_boards_board_count%             {boards:board_count}              boards loaded
 * </pre>
 *
 * In the percent form the underscore also separates arguments, so poll and guestbook ids with an underscore only work
 * in the brace form. A ranking is named by its source in lower case with every other character than a-z and 0-9
 * turned into {@code _}: {@code stat:PLAY_ONE_MINUTE} is {@code stat_play_one_minute}, and the part after the kind
 * ({@code play_one_minute}) is accepted as well when it is unique.
 */
final class BoardsPlaceholders {
    static final String NAMESPACE = "boards";

    /** What the values read; the plugin's fields are read at call time, so reloads are seen at once. */
    interface Source {
        boolean on(Feature feature);
        Polls polls();
        Guestbooks guestbooks();
        Collection<String> rankingSources();
        List<Leaderboards.Ranked> ranking(String source);
        String format(String source, double value);
        int boards();
    }

    private BoardsPlaceholders() {}

    /** Registers the namespace; {@code describe} turns a language key into a description (a {@code Lang.ref}). */
    static PlaceholderNamespace register(PlaceholderInterface registry, Plugin owner, Source source, UnaryOperator<String> describe) {
        PlaceholderNamespace ns = registry.register(owner, NAMESPACE);
        ns.resolver("votes", describe.apply("placeholder.votes"), "<poll> [option]", c -> {
            if (!polls(source)) return "";
            String poll = id(c.argument(0));
            if (poll == null) return null;
            String option = id(c.argument(1));
            return option == null ? source.polls().total(poll) : source.polls().count(poll, option);
        });
        ns.resolver("votes_percent", describe.apply("placeholder.votes_percent"), "<poll> <option>", c -> {
            if (!polls(source)) return "";
            String poll = id(c.argument(0)), option = id(c.argument(1));
            return poll == null || option == null ? null : source.polls().percent(poll, option);
        });
        ns.resolver("vote", describe.apply("placeholder.vote"), "<poll>", c -> {
            if (!polls(source)) return "";
            String poll = id(c.argument(0));
            if (poll == null) return null;
            String choice = source.polls().choice(poll, c.viewerId());
            return choice == null ? "-" : choice;
        });
        ns.resolver("poll_open", describe.apply("placeholder.poll_open"), "<poll>", c -> {
            if (!polls(source)) return "";
            String poll = id(c.argument(0));
            return poll == null ? null : !source.polls().closed(poll);
        });
        ns.resolver("guestbook_count", describe.apply("placeholder.guestbook_count"), "<book>", c -> {
            if (!guestbooks(source)) return "";
            String book = id(c.argument(0));
            return book == null ? null : source.guestbooks().entries(book).size();
        });
        ns.resolver("guestbook_name", describe.apply("placeholder.guestbook_name"), "<book> <n>", c -> entry(source, c, true));
        ns.resolver("guestbook_text", describe.apply("placeholder.guestbook_text"), "<book> <n>", c -> entry(source, c, false));
        ns.resolver("rank", describe.apply("placeholder.rank"), "<ranking>", c -> {
            if (!rankings(source)) return "";
            Leaderboards.Ranked own = own(source, c, c.rest(0));
            return own == null ? "" : own.rank();
        });
        ns.resolver("rank_value", describe.apply("placeholder.rank_value"), "<ranking>", c -> {
            if (!rankings(source)) return "";
            String ranking = ranking(source, c.rest(0));
            Leaderboards.Ranked own = own(source, c, c.rest(0));
            return own == null || ranking == null ? "" : source.format(ranking, own.value());
        });
        ns.resolver("top_name", describe.apply("placeholder.top_name"), "<ranking> <place>", c -> top(source, c, true));
        ns.resolver("top_value", describe.apply("placeholder.top_value"), "<ranking> <place>", c -> top(source, c, false));
        ns.global("board_count", describe.apply("placeholder.board_count"), () -> source.on(Feature.BOARDS) ? source.boards() : "");
        return ns;
    }

    private static boolean polls(Source s) { return s.on(Feature.BOARDS) && s.on(Feature.POLLS) && s.polls() != null; }

    private static boolean guestbooks(Source s) { return s.on(Feature.BOARDS) && s.on(Feature.GUESTBOOKS) && s.guestbooks() != null; }

    private static boolean rankings(Source s) { return s.on(Feature.BOARDS) && s.on(Feature.RANKINGS); }

    /** Lower-case poll or guestbook id, null when absent or not an id. */
    private static String id(String raw) {
        if (raw == null) return null;
        String id = raw.toLowerCase(Locale.ROOT);
        return Polls.ID.matcher(id).matches() ? id : null;
    }

    private static Object entry(Source source, PlaceholderContext c, boolean name) {
        if (!guestbooks(source)) return "";
        String book = id(c.argument(0));
        int n = c.intArgument(1, 1);
        if (book == null) return null;
        List<Guestbooks.Entry> entries = source.guestbooks().entries(book);
        if (n < 1 || n > entries.size()) return "";
        Guestbooks.Entry entry = entries.get(n - 1);
        return name ? entry.name() : entry.text();
    }

    /** The ranking source a slug names: the full slug ({@code stat_play_one_minute}) or, when unique, the part after the kind. */
    static String ranking(Source source, String slug) {
        if (slug == null || slug.isBlank()) return null;
        String wanted = slug(slug);
        String partial = null;
        int matches = 0;
        for (String candidate : source.rankingSources()) {
            String full = slug(candidate);
            if (full.equals(wanted)) return candidate;
            int colon = candidate.indexOf(':');
            if (colon >= 0 && slug(candidate.substring(colon + 1)).equals(wanted)) { partial = candidate; matches++; }
        }
        return matches == 1 ? partial : null;
    }

    static String slug(String text) {
        StringBuilder out = new StringBuilder();
        for (char ch : text.toLowerCase(Locale.ROOT).toCharArray()) out.append(ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9' ? ch : '_');
        return out.toString();
    }

    private static Leaderboards.Ranked own(Source source, PlaceholderContext c, String slug) {
        String ranking = ranking(source, slug);
        if (ranking == null || c.viewer() == null || c.viewer().getName() == null) return null;
        return Leaderboards.find(source.ranking(ranking), c.viewer().getName());
    }

    /** {@code top_name}/{@code top_value}: the last argument is the place, the ones before it name the ranking. */
    private static Object top(Source source, PlaceholderContext c, boolean name) {
        if (!rankings(source)) return "";
        List<String> args = c.arguments();
        if (args.size() < 2) return null;
        int place;
        try { place = Integer.parseInt(args.getLast()); } catch (NumberFormatException notANumber) { return null; }
        String ranking = ranking(source, String.join("_", args.subList(0, args.size() - 1)));
        if (ranking == null) return "";
        List<Leaderboards.Ranked> rows = source.ranking(ranking);
        if (place < 1 || place > rows.size()) return "";
        Leaderboards.Ranked row = rows.get(place - 1);
        return name ? row.name() : source.format(ranking, row.value());
    }
}
