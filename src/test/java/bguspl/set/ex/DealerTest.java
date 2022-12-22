package bguspl.set.ex;

import bguspl.set.Config;
import bguspl.set.Env;
import bguspl.set.UserInterface;
import bguspl.set.Util;
import bguspl.set.ex.TableTest.MockLogger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;

import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.booleanThat;
import static org.mockito.Mockito.when;

import bguspl.set.*;;


class DealerTest {

    private Dealer dealer;
    private Config config;
    Player [] players;
    private Integer[] slotToCard;
    private Integer[] cardToSlot;

    @BeforeEach
    void setUp() {
        // purposely do not find the configuration files (use defaults here).

        Properties properties = new Properties();
        properties.put("Rows", "2");
        properties.put("Columns", "2");
        properties.put("FeatureSize", "3");
        properties.put("FeatureCount", "4");
        properties.put("TableDelaySeconds", "0");
        properties.put("PlayerKeys1", "81,87,69,82");
        properties.put("PlayerKeys2", "85,73,79,80");
        MockLogger logger = new MockLogger();

        config = new Config(logger, properties);
        slotToCard = new Integer[config.tableSize];
        cardToSlot = new Integer[config.deckSize];


        Env env = new Env(logger, config, new TableTest.MockUserInterface(), new TableTest.MockUtil());
        Table table = new Table(env,slotToCard,cardToSlot);

        players=  new Player[1];
        dealer= new Dealer(env, table, players);


        players[0] = new Player(env, dealer, table, 0, false);





    }

    @Test
    void placeCardsOnTable()
    {
        int expected = dealer.getDeckSize() - config.tableSize;
        dealer.placeCardsOnTableForTests();

        assertEquals(expected, dealer.getDeckSize());
    }

    @Test

    void removeAllCardsFromTable() {


        dealer.removeAllCardsFromTableForTests();

        for (int i = 0; i < slotToCard.length; i++) {
            assertNull(slotToCard[i]);
        }
    }
}