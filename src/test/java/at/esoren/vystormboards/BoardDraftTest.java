package at.esoren.vystormboards;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.configuration.file.YamlConfiguration;
import static org.junit.jupiter.api.Assertions.*;

class BoardDraftTest {
    @TempDir Path dir;
    private static final String DOCUMENT = """
        metadata: retain-me
        boards:
          demo:
            world: world
            width: 2
            height: 1
            page-seconds: 12
            maps: [4, 5]
            elements:
              - {type: rect, x: 0, y: 0, w: 256, h: 128, color: black}
            pages:
              - [{type: text, text: first, x: 8, y: 8, size: 18}]
              - [{type: text, text: second, x: 9, y: 9, size: 20}]
              - [{type: text, text: third}]
            clicks:
              - {page: 1, navigate: '2'}
              - {page: 2, message: hello}
              - {page: 3, navigate: next}
          other:
            world: elsewhere
            unknown: preserved
        """;
    @Test void draftsStayDetachedUntilSaveAndPreserveOtherConfiguration() throws Exception {
        Path file=dir.resolve("boards.yml"); Files.writeString(file,DOCUMENT);
        BoardDraft d=new BoardDraft("demo",DOCUMENT); d.text(0,0,"Changed"); d.interval(20);
        assertEquals(DOCUMENT,Files.readString(file));
        assertEquals(BoardDraft.Save.SAVED,d.save(Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, file));
        YamlConfiguration saved=YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals("retain-me",saved.getString("metadata"));
        assertEquals("preserved",saved.getString("boards.other.unknown"));
        assertEquals(List.of(4,5),saved.getIntegerList("boards.demo.maps"));
        Board b=Board.read("demo",saved.getConfigurationSection("boards.demo"));
        assertEquals(20,b.pageSeconds); assertEquals(1,b.elements.size());
        assertEquals("Changed",((Board.Text)b.pages.get(0).get(0)).text());
    }
    @Test void concurrentEditRefusesOverwrite() throws Exception {
        Path file=dir.resolve("boards.yml"); Files.writeString(file,DOCUMENT);
        BoardDraft d=new BoardDraft("demo",DOCUMENT);d.text(0,0,"new");
        String external=DOCUMENT+"external: true\n";Files.writeString(file,external);
        assertEquals(BoardDraft.Save.CONFLICT,d.save(Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, file)); assertEquals(external,Files.readString(file));
    }
    @Test void pageRemovalAndMovesKeepBindingsOnTheirIntendedPages() throws Exception {
        BoardDraft moved=new BoardDraft("demo",DOCUMENT);assertTrue(moved.movePage(0,2));
        Board m=moved.board();assertEquals("third",((Board.Text)m.pages.get(0).get(0)).text());
        assertEquals(3,m.clicks.get(0).page());assertEquals("2",m.clicks.get(0).navigate());assertEquals(1,m.clicks.get(2).page());
        BoardDraft removed=new BoardDraft("demo",DOCUMENT);assertTrue(removed.removePage(1));
        Board r=removed.board();assertEquals(2,r.pages.size());assertEquals(1,r.clicks.size());
        assertEquals(2,r.clicks.get(0).page());assertEquals("next",r.clicks.get(0).navigate());
    }
    @Test void clonedTextDoesNotModifyOriginalAndLimitsAreEnforced() throws Exception {
        BoardDraft d=new BoardDraft("demo",DOCUMENT); assertTrue(d.addPage(0));assertTrue(d.text(3,0,"copy"));
        assertEquals("first",d.elements(0).get(0).get("text"));
        assertFalse(d.text(0,0,"bad\nline"));assertFalse(d.text(0,0,"a".repeat(241)));
        while(d.count()<32)assertTrue(d.addPage(-1));assertFalse(d.addPage(-1));
        for(int i=0;i<45;i++)assertTrue(d.text(31,-1,"line"));assertFalse(d.text(31,-1,"overflow"));
        assertThrows(IllegalArgumentException.class,()->d.interval(0));
    }
    @Test void unreadableFileDoesNotClaimSaved() throws Exception {
        BoardDraft d=new BoardDraft("demo",DOCUMENT);assertEquals(BoardDraft.Save.FAILED,d.save(Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, dir));
    }
    @Test void thumbnailPreservesAspectRatioWithLetterboxing() {
        int[] pixels=new int[256*128];Arrays.fill(pixels,0xffffff);
        var image=BoardEditor.thumbnail(pixels,256,128);
        assertEquals(0xffffff,image.getRGB(64,64)&0xffffff);
        assertEquals(0,image.getRGB(64,1)&0xffffff);
        assertEquals(0,image.getRGB(64,127)&0xffffff);
    }
    @Test void corruptNavigationDoesNotPartiallyReorderPages() throws Exception {
        BoardDraft d=new BoardDraft("demo",DOCUMENT.replace("navigate: '2'","navigate: '999999999999999999999999'"));
        assertThrows(IllegalArgumentException.class,()->d.movePage(0,1));
        assertEquals("first",d.elements(0).get(0).get("text"));
    }
}
