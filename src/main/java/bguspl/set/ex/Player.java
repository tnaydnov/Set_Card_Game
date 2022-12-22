package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /**
     * The dealer
     */
    private Dealer dealer;

    /**
     * A queue of key presses
     */
    private BlockingQueue<Integer> keyPresses;

    /**
     * A list of the player's tokens
     */
    private List<Integer> tokens;

    /**
     * Lock
     */
    public final Object Lock = new Object();

    /**
     * Penalty or Point index:
     * 1 = Point
     * 0 = Penalty
     * -1 = None (default)
     */
    public int penaltyOrPoint;

    /**
     * Freeze boolean to determine if the player is waiting for the dealer to check its set
     */
    public volatile boolean onFreeze;



    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        keyPresses = new ArrayBlockingQueue<>(3, true);
        this.dealer = dealer;
        tokens = new LinkedList<>();
        penaltyOrPoint = -1;
        onFreeze = false;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate)
        {
            // TODO implement main player loop
                try
                {
                    if (!keyPresses.isEmpty())
                    {
                        int keyPress;
                        synchronized (keyPresses)
                        {
                            keyPress = keyPresses.remove();
                            keyPresses.notifyAll();
                        }
                        if (tokens.contains(keyPress)) //If the player already has a token on that card
                        {
                            removeToken(keyPress);
                        }
                        else //If the player doesn't have a token on that card
                        {
                            table.semaphore.acquire();
                            synchronized (dealer.playerSets)
                            {
                                synchronized (table)
                                {
                                    if (table.slotToCard[keyPress] != null && tokens.size() < 3)
                                    {
                                        table.placeToken(id, keyPress);
                                        tokens.add(keyPress);
                                        if (tokens.size() == 3) //Third token is placed
                                        {
                                            onFreeze = true;
                                            dealer.playerSets.add(id);
                                            dealer.playerSets.notifyAll();
                                        }
                                    }
                                }
                            }
                            table.semaphore.release();
                        }
                    }
                    if (onFreeze) { //If the set was sent to the dealer
                        try
                        {
                            synchronized (Lock)
                            {
                                if (penaltyOrPoint == -1) //Waiting for dealer
                                {
                                    Lock.wait();
                                }
                                if (penaltyOrPoint == 1) //Legal set
                                { // legal set
                                    point();
                                }
                                else if (penaltyOrPoint == 0) //Illegal set
                                {
                                    penalty();
                                }
                            }
                        }
                        catch (InterruptedException ignored) {}
                    }
                }
                catch(InterruptedException ignored) {}
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                // TODO implement player key press simulator
                int random = (int) (Math.random() * 12);
                synchronized (keyPresses)
                {
                    if(keyPresses.remainingCapacity() != 0)
                    {
                        Player.this.keyPressed(random);
                    }
                }
            }
            env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        // TODO implement
        terminate = true;
        if (!human) {
            aiThread.interrupt();
        }
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (!onFreeze && !dealer.gameFreeze)
        {
            try
            {
                synchronized (keyPresses)
                {
                    keyPresses.add(slot);
                    keyPresses.notifyAll();
                }
            }
            catch (IllegalStateException ignored) {}
        }
    }



    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        // TODO implement

        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        penaltyOrPoint = -1;
        onFreeze = false;
        setFreeze(env.config.pointFreezeMillis);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        // TODO implement
        penaltyOrPoint = -1;
        onFreeze = false;
        setFreeze(env.config.penaltyFreezeMillis);
    }

    public int score() {
        return score;
    }

    private void removeToken(int slot) {
        tokens.remove((Object)slot);
        table.removeToken(this.id, slot);
    }

    public void removeTokenFromList(int slot) {
        tokens.remove((Object) slot);
    }

    public boolean getTerminate() {
        return terminate;
    }

    public Thread getPlayerThread() {
        return playerThread;
    }

    public void setFreeze(long freezeTime) {
        try
        {
            // left - second left to sleep
            for (long left = freezeTime / 1000; left > 0; left--) {
                env.ui.setFreeze(id, left * 1000);
                Thread.sleep(1000);
            }
            env.ui.setFreeze(id, 0);
            penaltyOrPoint = -1;
        }
        catch (InterruptedException ignored) {}
    }

    public List<Integer> getTokens() {
        return tokens;
    }
}

