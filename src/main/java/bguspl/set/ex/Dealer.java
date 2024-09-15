package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;
    private long timeWhenReset = 0;
    private boolean needReshuffle = false;
    public ConcurrentLinkedQueue<Player> checkList;
    public Player toCheck;
    Thread [] playerThreads;


    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        toCheck = null;
        checkList = new ConcurrentLinkedQueue<Player>();
        playerThreads = new Thread[players.length];
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        System.out.println("thread " + Thread.currentThread().getName() + " starting.");
        for(int i = 0 ; i < players.length;i++){
            playerThreads[i] = new Thread(players[i], "Player " + i);
            playerThreads[i].start();
            try{
                synchronized(this){this.wait();}
            }catch(InterruptedException ignored){}
            
        }
        
        while (!shouldFinish()) {
            for(Player player:players){
                try{
                    player.playerLock.acquire();
                }catch(InterruptedException ignored){}
            }
            placeCardsOnTable();
            for(Player player:players){
                player.playerLock.release();
            }
            timerLoop();
            updateTimerDisplay(false);
            for(Player player:players){
                try{
                    player.playerLock.acquire();
                }catch(InterruptedException ignored){}
            }
            removeAllCardsFromTable();
            for(Player player:players){
                player.playerLock.release();
            }
        }
        announceWinners();
        exitGame();

        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        System.out.println("thread " + Thread.currentThread().getName() + " terminated");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        
        
        updateTimerDisplay(true);
        while (!terminate && System.currentTimeMillis() < reshuffleTime && !needReshuffle) {
            updateTimerDisplay(false);
            sleepUntilWokenOrTimeout();
            
            while(!checkList.isEmpty() && !terminate){
                toCheck = checkList.remove();
                isCorrect();
                if(toCheck.correct == true){
                    for(Player player:players){
                        try{
                            player.playerLock.acquire();
                        }catch(InterruptedException ignored){}
                    }
                    removeCardsFromTable();
                    placeCardsOnTable();
                    if(env.config.turnTimeoutMillis > 0)
                        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
                    else if(env.config.turnTimeoutMillis == 0)
                        timeWhenReset = System.currentTimeMillis();
                    for(Player player:players){
                        player.playerLock.release();
                    }
                }
                synchronized(this){this.notifyAll();} 
            }
                                 
        }
        
        
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return (terminate || env.util.findSets(deck, 1).size() == 0);
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        if(toCheck!=null && toCheck.correct==true){
            int [] slots = new int[3];
            for(int i = 0 ; i < 3 ; i ++){
                slots[i] = toCheck.tokens[i];
            }
            for(Player player:players){
                for(int i = 0 ; i < toCheck.tokens.length;i++){
                    player.removeToken(slots[i]);
                }
                
            }
            for(int i = 0 ; i < toCheck.tokens.length;i++){
                table.removeCard(slots[i]);
            }
        }
        toCheck = null;
    }
        

            
    
        

    

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        if(table.countCards() == 0)
           Collections.shuffle(deck);
        while(!deck.isEmpty() && table.countCards() < table.slotToCard.length){
            boolean placed = false;
            for(int i = 0 ; i < table.slotToCard.length && !placed;i++){
                if(table.slotToCard[i] == null){
                    table.placeCard(deck.remove(deck.size()-1), i);
                    placed = true;
                }
            }
        }
        if(env.config.turnTimeoutMillis <=0){
            List <Integer> cardsOnTable = new LinkedList<>();
            for(int i = 0; i < env.config.tableSize;i++){
                if(table.slotToCard[i]!=null)
                    cardsOnTable.add(table.slotToCard[i]);
            }
            if(env.util.findSets(cardsOnTable, 1).size() == 0){
                needReshuffle = true;
            }
            else{
                needReshuffle = false;
            }
        }
        if(env.config.hints){
            table.hints();
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        long sleepInterval = 1000;
        if(env.config.turnTimeoutMillis > 0){
            if(reshuffleTime - System.currentTimeMillis() < env.config.turnTimeoutWarningMillis)
                sleepInterval = 10;
            synchronized(this){
                if(checkList.isEmpty()){
                    try{
                    this.wait(sleepInterval);
                    }
                    catch(InterruptedException ignored){}
                }
            }
        }
        else if(env.config.turnTimeoutMillis == 0){
            synchronized(this){
                if(checkList.isEmpty()){
                    try{
                    this.wait(sleepInterval);
                    }
                    catch(InterruptedException ignored){}
                }
            }
        }
        else{
            synchronized(this){
                if(checkList.isEmpty()){
                    try{
                    this.wait();
                    }
                    catch(InterruptedException ignored){}
                }
            }
        }
           
       
       
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        long displayTime = reshuffleTime - System.currentTimeMillis();
        if(reset){
            if(env.config.turnTimeoutMillis > 0)
                reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            timeWhenReset = System.currentTimeMillis();
        }
        if(env.config.turnTimeoutMillis > 0){
            if(displayTime <= env.config.turnTimeoutWarningMillis){
                env.ui.setCountdown(displayTime, true);
            }
            else{
                double doubleDisplayTime =  (double)((reshuffleTime-System.currentTimeMillis()))/1000;
                displayTime = (Long)(Math.round(doubleDisplayTime)*1000);
                env.ui.setCountdown(displayTime, false);
            }
            
            
        }
        else if(env.config.turnTimeoutMillis == 0){
            displayTime = System.currentTimeMillis() - timeWhenReset;
            env.ui.setElapsed(displayTime);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        for(Player player:players){
            player.removeToken(player.tokens[0]);
            player.removeToken(player.tokens[1]);
            player.removeToken(player.tokens[2]);
        }
        for(int i = 0 ; i < table.slotToCard.length;i++){
            if(table.slotToCard[i] != null){
                deck.add(table.slotToCard[i]);
                table.removeCard(i);
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int max = 0;
        int numOfWinners = 0;
        for(Player player:players){
            if(player.score() > max ){
                numOfWinners = 1;
                max = player.score();
            }
            else if(player.score() == max){
                numOfWinners++;
            }
        }
        int [] winners = new int[numOfWinners];
        int i = 0;
        for(Player player:players){
            if(player.score() == max){
                winners[i] = player.id;
                i++;
            }
        }
        env.ui.announceWinner(winners);

    }

    private void isCorrect(){
        if(toCheck.tokenCounter == 3){
            int[] possibleSet = new int[3];
            for(int i = 0 ; i < possibleSet.length ; i++){
                possibleSet[i] = table.slotToCard[toCheck.tokens[i]];
            }
            if(env.util.testSet(possibleSet)){
                toCheck.correct = true;
            }
            else{
                toCheck.correct = false;
            }
            toCheck.checked = true;
            
        }
        else{
            toCheck.correct = false;
        }
        
    }



    private void exitGame(){
       
        for(int i = players.length - 1 ; i >= 0 ; i--){
            players[i].terminate();
            synchronized(this){this.notifyAll();}
            synchronized(playerThreads[i]){playerThreads[i].notifyAll();}
            try{
                playerThreads[i].join();
            }catch (InterruptedException ignored) {}
        }
        
    }
}