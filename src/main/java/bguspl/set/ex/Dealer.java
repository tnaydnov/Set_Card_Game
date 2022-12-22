package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;
    public boolean gameFreeze = false;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;


    public BlockingQueue<Integer> playerSets;





    private long timer;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        playerSets = new ArrayBlockingQueue<>(env.config.players, true);
        timer = 1000;
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        for(Player player: players)
        {
            Thread playerThread = new Thread(player, "player");
            playerThread.start();
        }
        Collections.shuffle(deck);
        while (!shouldFinish()) {
            placeCardsOnTable();
            gameFreeze = false;
            updateTimerDisplay(true);
            timerLoop();
            updateTimerDisplay(true);
            removeAllCardsFromTable();
        }
        announceWinners();
        terminate();
        try { Thread.sleep(env.config.endGamePauseMillies); }
        catch(InterruptedException ex) {}
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime)
        {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
            if(!checkForLegalSets()) //If no more sets on the table - finish the inner loop (60 seconds loop)
            {
                break;
            }
        }
    }

    private boolean checkForLegalSets() {
        List<Integer> board = new LinkedList<>();
        for(int i = 0; i < 12; i++) {
            if(table.slotToCard[i] != null)
            {
                board.add(table.slotToCard[i]);
            }
        }
        if(env.util.findSets(board, 1).size() == 0) {
            if (deck.isEmpty())
            {
                terminate();
            }
            return false;
        }
        return true;
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate()
    {
        // TODO implement
        try {
            for (int i = players.length - 1; i >= 0; i--) {
                players[i].terminate(); //Setting terminate to true & interrupting the AI thread if exists
                players[i].getPlayerThread().interrupt(); //interrupting the player thread if exists
                players[i].getPlayerThread().join(); //Waiting till the player thread is interrupted
            }
            terminate = true;
        }
        catch (InterruptedException ignored) {}
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        // TODO implement
        synchronized (playerSets)
        {
            while (!playerSets.isEmpty()) //While there's a player waiting for set check
            {
                int playerID = playerSets.remove(); //The first player's ID
                List<Integer> playerTokens = players[playerID].getTokens(); //Getting the player's tokens
                int[] potentialSet = playerTokens.stream().mapToInt(Integer::intValue).toArray();
                int[] slotsToRemove = playerTokens.stream().mapToInt(Integer::intValue).toArray();
                for (int i = 0; i < potentialSet.length; i++) { //Turning the slots to cards
                        potentialSet[i] = table.slotToCard[potentialSet[i]];
                }
                if (env.util.testSet(potentialSet) && potentialSet.length == 3) { //If the set is legal
                    players[playerID].penaltyOrPoint = 1;
                    synchronized (table) {
                        for (int i = 0; i < slotsToRemove.length; i++) { //Removing the tokens & cards of the set
                            removeTokensFromCard(slotsToRemove[i]);
                            table.removeCard(slotsToRemove[i]);
                        }
                    }
                    updateTimerDisplay(true); //Resetting the timer to 60
                }
                else if (!env.util.testSet(potentialSet)) //If the set is not legal
                {
                    players[playerID].penaltyOrPoint = 0;
                }
                synchronized (players[playerID].Lock)
                {
                    players[playerID].Lock.notifyAll(); //Waking the player from the wait
                }
            }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        // TODO implement
        boolean placed = false;
        synchronized (table) {
            for (int i = 0; i < table.slotToCard.length && !deck.isEmpty(); i++) {
                if (table.slotToCard[i] == null) {
                    table.placeCard(deck.remove(0), i);
                    placed = true;
                }
            }
            if (placed && env.config.hints) // if a card was placed - show hints
                table.hints();
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        // TODO implement
        synchronized (playerSets) {
            try {
                if (env.config.turnTimeoutMillis >= 0) {
                    long waitingTime = timer - System.currentTimeMillis();
                    if (env.config.turnTimeoutMillis > 0) {
                        if (!((reshuffleTime - System.currentTimeMillis()) < 5000) && playerSets.isEmpty() && waitingTime > 25)
                            playerSets.wait(waitingTime - 25);
                        else if (((reshuffleTime - System.currentTimeMillis()) < 5000) && playerSets.isEmpty()) //If it's the last 5 seconds - warn & update the time faster
                            playerSets.wait(10);
                    }
                    else if (env.config.turnTimeoutMillis == 0) {
                        waitingTime = timer - System.currentTimeMillis();
                        if (playerSets.isEmpty() && waitingTime > 25)
                            playerSets.wait(waitingTime - 25);
                    }
                }
                else if (env.config.turnTimeoutMillis < 0) {
                    if (playerSets.isEmpty())
                        playerSets.wait();
                }
            }
            catch (InterruptedException ignored) {}
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        // TODO implement
        if (reset && env.config.turnTimeoutMillis > 0) {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        }
        else if (reset && env.config.turnTimeoutMillis == 0) {
            reshuffleTime = System.currentTimeMillis();
        }
        if (reset || ((reshuffleTime - System.currentTimeMillis()) < 5000) ||  System.currentTimeMillis() >= timer - 25)
        {
            if (env.config.turnTimeoutMillis > 0) {
                long timeDisplay = reshuffleTime - System.currentTimeMillis();
                env.ui.setCountdown(timeDisplay, ((reshuffleTime - System.currentTimeMillis()) < 5000));
                timer = Math.min(reshuffleTime - timeDisplay + 1000, reshuffleTime - env.config.turnTimeoutWarningMillis);
            }
            else if (env.config.turnTimeoutMillis == 0) {
                long timeDisplay = System.currentTimeMillis() - reshuffleTime;
                env.ui.setElapsed(timeDisplay);
                timer = timeDisplay + 1000;
            }
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        gameFreeze = true; //Don't allow key press while removing all cards
        synchronized (table) {
            for (int i = 0; i < table.slotToCard.length; i++) {
                if (table.slotToCard[i] != null) {
                    deck.add(table.slotToCard[i]);
                    for (Player player : players) { //Remove all the tokens from that card
                        player.removeTokenFromList(i);
                    }
                    table.removeCard(i); //Remove the card
                }
            }
        }
        Collections.shuffle(deck);
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        // TODO implement
        int highestScore = 0;
        List<Integer> winners = new LinkedList<Integer>();
        for(Player player: players) {
            if (player.score() > highestScore) {
                highestScore = player.score();
            }
        }
        for(Player player: players) {
            if (highestScore == player.score()) {
                winners.add(player.id);
            }
        }
        int[] winnersFinal = new int[winners.size()];
        for(int i = 0; i < winners.size(); i++) {
            winnersFinal[i] = winners.get(i);
        }
        env.ui.announceWinner(winnersFinal);
    }

    public void removeTokensFromCard(int slot) {
        for(Player player: players) {
            player.removeTokenFromList(slot);
        }
    }

    public void placeCardsOnTableForTests() {
        boolean placed = false;
        synchronized (table) {
            for (int i = 0; i < table.slotToCard.length && !deck.isEmpty(); i++) {
                if (table.slotToCard[i] == null) {
                    table.placeCard(deck.remove(0), i);
                    placed = true;
                }
            }
            if (placed && env.config.hints) // if a card was placed - show hints
                table.hints();
        }
    }

    public void removeAllCardsFromTableForTests() {
        gameFreeze = true; //Don't allow key press while removing all cards
        synchronized (table) {
            for (int i = 0; i < table.slotToCard.length; i++) {
                if (table.slotToCard[i] != null) {
                    deck.add(table.slotToCard[i]);
                    for (Player player : players) { //Remove all the tokens from that card
                        player.removeTokenFromList(i);
                    }
                    table.removeCard(i); //Remove the card
                }
            }
        }
        Collections.shuffle(deck);
    }

    public int getDeckSize() {
        return deck.size();
    }
}
