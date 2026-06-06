<script lang="ts">
  import { playWithBotStore } from '../playWithBot/playWithBotStore.svelte';
  import Chessground from './board/Chessground.svelte';
  import DiceBox from './DiceBox.svelte';
  import { ChevronLeft, ChevronRight, RefreshCw, Flag, Smile } from 'lucide-svelte';
  import type { Api } from '@lichess-org/chessground/api';
  import type { Key } from '@lichess-org/chessground/types';

  import '@lichess-org/chessground/assets/chessground.base.css';
  import '@lichess-org/chessground/assets/chessground.brown.css';
  import '@lichess-org/chessground/assets/chessground.cburnett.css';

  let cgApi = $state<Api | undefined>(undefined);

  const fen = $derived(playWithBotStore.currentBoardFen);
  const orientation = $derived((playWithBotStore.playerColor === 'w' ? 'white' : 'black') as 'white' | 'black');

  const movableConfig = $derived({
    free: false,
    dests: playWithBotStore.legalMovesDests,
    color: orientation
  });

  const eventsConfig = $derived({
    move: handleMove
  });

  function handleMove(orig: Key, dest: Key) {
    cgApi?.set({ turnColor: orientation });
    playWithBotStore.handleBoardMove(orig, dest);
  }

  function handleSelectPromotion(piece: string) {
    playWithBotStore.completePromotion(piece);
  }

  function handleCancelPromotion() {
    playWithBotStore.cancelPromotion();
  }

  function formatTime(ms: number): string {
    if (ms <= 0) return '00:00';
    const totalSeconds = ms / 1000;
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = Math.floor(totalSeconds % 60);
    return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
  }

  function getBotDisplayName(algo: string): string {
    return algo === 'greedy' ? 'Greedy Bot 🤑' : 'Random Bot 🎲';
  }
</script>

<div class="flex flex-col lg:flex-row gap-8 w-full max-w-6xl mx-auto py-4 px-2 items-start justify-center">

  <!-- Left Side: Board and Timers -->
  <div class="w-full max-w-[560px] flex flex-col gap-4 mx-auto shrink-0">

    <!-- Top Player: Bot Info & Timer -->
    <div class="flex justify-between items-center bg-slate-900/50 border border-slate-800/80 p-3 rounded-xl shadow-lg shadow-black/20 backdrop-blur-md">
      <div class="flex items-center gap-3">
        <div class="w-9 h-9 rounded-xl bg-slate-850 flex items-center justify-center font-bold text-sm text-slate-400 border border-slate-750">
          {playWithBotStore.playerColor === 'w' ? 'B' : 'W'}
        </div>
        <div class="flex flex-col">
          <span class="font-bold text-slate-100 text-sm">
            {getBotDisplayName(playWithBotStore.botAlgorithm)}
          </span>
          <div class="flex items-center gap-1.5 mt-0.5">
            {#if playWithBotStore.gameStatus === 'bot_thinking'}
              <span class="w-2 h-2 rounded-full bg-yellow-500 animate-ping"></span>
              <span class="text-[10px] text-yellow-400 font-semibold uppercase tracking-wider">thinking...</span>
            {:else if playWithBotStore.gameStatus === 'rolling' && playWithBotStore.activeColor === playWithBotStore.botColor}
              <span class="w-2 h-2 rounded-full bg-indigo-500 animate-pulse"></span>
              <span class="text-[10px] text-indigo-400 font-semibold uppercase tracking-wider">rolling...</span>
            {:else}
              <span class="w-2 h-2 rounded-full bg-slate-600"></span>
              <span class="text-[10px] text-slate-400 font-medium uppercase tracking-wider">waiting</span>
            {/if}
          </div>
        </div>
      </div>

      {#if playWithBotStore.timeLimit !== null}
        <div class="font-mono font-bold text-lg px-3 py-1.5 rounded-lg border bg-slate-950/80 transition-all duration-300 {playWithBotStore.activeColor === playWithBotStore.botColor ? 'text-amber-400 border-amber-500/40 shadow-[0_0_12px_rgba(245,158,11,0.2)]' : 'text-slate-500 border-slate-850'}">
          {formatTime(playWithBotStore.botTimeLeft)}
        </div>
      {:else}
        <span class="text-xs text-slate-400 font-semibold px-2.5 py-1 rounded-lg bg-slate-900/60 border border-slate-800">Casual</span>
      {/if}
    </div>

    <!-- Board Area -->
    <div class="w-full aspect-square bg-[#0f172a] rounded-2xl overflow-hidden shadow-[0_20px_50px_rgba(0,0,0,0.8)] border border-slate-800 relative">
      <Chessground
        {fen}
        {orientation}
        movable={movableConfig}
        events={eventsConfig}
        bind:api={cgApi}
        class="absolute inset-0"
      />

      <!-- Pawn Promotion Selector Overlay -->
      {#if playWithBotStore.pendingPromotion}
        {@const colorClass = playWithBotStore.pendingPromotion.color === 'w' ? 'white' : 'black'}
        <div class="absolute inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm">
          <div class="bg-slate-900 border border-slate-700/80 rounded-2xl shadow-2xl p-6 flex flex-col items-center gap-4 max-w-xs w-[90%]">
            <h4 class="text-lg font-bold text-white">Promote to:</h4>
            <div class="flex gap-3 cg-wrap cg-board-wrap is2d">
              <cg-board class="!static !w-auto !h-auto !bg-transparent flex gap-3">
                {#each playWithBotStore.pendingPromotion.availablePieces as p}
                  {@const pieceType = p === 'q' ? 'queen' : p === 'r' ? 'rook' : p === 'b' ? 'bishop' : 'knight'}
                  <button
                    onclick={() => handleSelectPromotion(p)}
                    class="w-14 h-14 hover:scale-110 hover:bg-slate-800 transition-all rounded-xl flex items-center justify-center relative cursor-pointer border border-slate-800"
                    aria-label="Promote to {pieceType}"
                  >
                    <piece class="{pieceType} {colorClass} !static !w-full !h-full"></piece>
                  </button>
                {/each}
              </cg-board>
            </div>
            <button
              onclick={handleCancelPromotion}
              class="mt-2 px-5 py-2 text-xs font-bold text-slate-300 hover:text-white bg-slate-800 hover:bg-slate-700 rounded-xl border border-slate-650 transition"
            >
              Cancel
            </button>
          </div>
        </div>
      {/if}
    </div>

    <!-- Bottom Player: Human Info & Timer -->
    <div class="flex justify-between items-center bg-slate-900/50 border border-slate-800/80 p-3 rounded-xl shadow-lg shadow-black/20 backdrop-blur-md">
      <div class="flex items-center gap-3">
        <div class="w-9 h-9 rounded-xl bg-indigo-950 flex items-center justify-center font-bold text-sm text-indigo-400 border border-indigo-900/50 shadow-inner">
          {playWithBotStore.playerColor === 'w' ? 'W' : 'B'}
        </div>
        <div class="flex flex-col">
          <span class="font-bold text-slate-100 text-sm">Anonymous</span>
          <div class="flex items-center gap-1.5 mt-0.5">
            {#if playWithBotStore.activeColor === playWithBotStore.playerColor && playWithBotStore.gameStatus === 'playing'}
              <span class="w-2 h-2 rounded-full bg-emerald-500 animate-ping"></span>
              <span class="text-[10px] text-emerald-400 font-semibold uppercase tracking-wider">your turn to move</span>
            {:else if playWithBotStore.activeColor === playWithBotStore.playerColor && playWithBotStore.gameStatus === 'rolling'}
              <span class="w-2 h-2 rounded-full bg-indigo-500 animate-pulse"></span>
              <span class="text-[10px] text-indigo-400 font-semibold uppercase tracking-wider">your turn to roll dice</span>
            {:else}
              <span class="w-2 h-2 rounded-full bg-slate-600"></span>
              <span class="text-[10px] text-slate-400 font-medium uppercase tracking-wider">waiting</span>
            {/if}
          </div>
        </div>
      </div>

      {#if playWithBotStore.timeLimit !== null}
        <div class="font-mono font-bold text-lg px-3 py-1.5 rounded-lg border bg-slate-950/80 transition-all duration-300 {playWithBotStore.activeColor === playWithBotStore.playerColor ? 'text-amber-400 border-amber-500/40 shadow-[0_0_12px_rgba(245,158,11,0.2)]' : 'text-slate-500 border-slate-850'}">
          {formatTime(playWithBotStore.playerTimeLeft)}
        </div>
      {:else}
        <span class="text-xs text-slate-400 font-semibold px-2.5 py-1 rounded-lg bg-slate-900/60 border border-slate-800">Casual</span>
      {/if}
    </div>

  </div>

  <!-- Right Side: Sidebar Controls and Game History -->
  <div class="w-full lg:w-[360px] flex flex-col gap-4 self-stretch shrink-0">

    <!-- Dice Box Panel -->
    <div class="bg-slate-900/50 border border-slate-800/80 backdrop-blur-md rounded-2xl p-4 shadow-xl flex flex-col gap-3">
      <span class="text-[10px] text-indigo-400 font-bold uppercase tracking-widest text-center">Dice Box</span>
      <DiceBox
        horizontal={true}
        dicesOverride={playWithBotStore.currentDice}
        isWhiteTurnOverride={playWithBotStore.activeColor === 'w'}
        showRollButton={playWithBotStore.canUserRoll}
        onRoll={() => playWithBotStore.rollDice()}
        isAnimatingRoll={playWithBotStore.isAnimatingRoll}
      />
    </div>

    <!-- Active Game Controls -->
    <div class="bg-slate-900/50 border border-slate-800/80 backdrop-blur-md rounded-2xl p-4 shadow-xl flex flex-col gap-3">
      <span class="text-[10px] text-slate-400 font-bold uppercase tracking-widest text-center">Controls</span>

      {#if ['playing', 'rolling', 'bot_thinking'].includes(playWithBotStore.gameStatus)}
        <div class="grid grid-cols-2 gap-3">
          <button
            onclick={() => playWithBotStore.resignGame()}
            class="flex items-center justify-center gap-2 px-4 py-2.5 text-xs font-bold bg-rose-950/20 hover:bg-rose-900/30 text-rose-400 hover:text-rose-350 border border-rose-900/30 rounded-xl transition-all duration-200 active:scale-95 cursor-pointer shadow-sm"
          >
            <Flag size={14} /> Resign
          </button>
          <button
            onclick={() => playWithBotStore.offerDraw()}
            class="flex items-center justify-center gap-2 px-4 py-2.5 text-xs font-bold bg-emerald-950/20 hover:bg-emerald-900/30 text-emerald-400 hover:text-emerald-350 border border-emerald-900/30 rounded-xl transition-all duration-200 active:scale-95 cursor-pointer shadow-sm"
          >
            <Smile size={14} /> Offer Draw
          </button>
        </div>
      {/if}

      <button
        onclick={() => playWithBotStore.endSession()}
        class="flex items-center justify-center gap-2 px-4 py-2.5 text-xs font-bold bg-slate-800 hover:bg-slate-750 text-slate-200 rounded-xl border border-slate-700/50 transition-all duration-200 active:scale-95 cursor-pointer"
      >
        <RefreshCw size={14} /> Leave Match
      </button>
    </div>

    <!-- Move History Block -->
    <div class="flex-1 min-h-[320px] bg-slate-900/50 border border-slate-800/80 backdrop-blur-md rounded-2xl p-4 shadow-xl flex flex-col gap-3">
      <span class="text-[10px] text-slate-400 font-bold uppercase tracking-widest text-center">Move History</span>

      <div class="flex-1 overflow-y-auto max-h-[300px] border border-slate-850 rounded-xl p-2 bg-slate-950/50 custom-scrollbar text-sm">
        {#if playWithBotStore.historyBlocks.length === 0}
          <div class="text-slate-500 italic text-center py-8">No moves yet</div>
        {:else}
          <div class="flex flex-col gap-0.5">
            {#each playWithBotStore.historyBlocks as block}
              <div class="grid grid-cols-12 gap-1 py-1 border-b border-slate-900/40 last:border-b-0 hover:bg-slate-800/10 rounded px-1 items-center">
                <!-- Turn Number -->
                <span class="col-span-2 text-slate-500 font-semibold font-mono text-xs">{block.turnNumber}.</span>

                <!-- White Moves -->
                <div class="col-span-5 flex flex-wrap gap-1">
                  {#each block.whiteMoves as m}
                    <button
                      class="text-xs px-2 py-0.5 rounded font-mono transition-all {playWithBotStore.currentMoveIndex === m.index ? 'bg-indigo-600 text-white font-bold shadow shadow-indigo-500/30' : 'text-slate-300 hover:bg-slate-800 hover:text-white'}"
                      onclick={() => playWithBotStore.setMoveIndex(m.index)}
                    >
                      {m.pieceIcon}{m.text}
                    </button>
                  {/each}
                </div>

                <!-- Black Moves -->
                <div class="col-span-5 flex flex-wrap gap-1">
                  {#each block.blackMoves as m}
                    <button
                      class="text-xs px-2 py-0.5 rounded font-mono transition-all {playWithBotStore.currentMoveIndex === m.index ? 'bg-indigo-600 text-white font-bold shadow shadow-indigo-500/30' : 'text-slate-300 hover:bg-slate-800 hover:text-white'}"
                      onclick={() => playWithBotStore.setMoveIndex(m.index)}
                    >
                      {m.pieceIcon}{m.text}
                    </button>
                  {/each}
                </div>
              </div>
            {/each}
          </div>
        {/if}
      </div>

      <!-- Nav controls (enabled if game ended) -->
      {#if ['victory', 'defeat', 'draw'].includes(playWithBotStore.gameStatus)}
        <div class="flex items-center justify-between border-t border-slate-800/60 pt-3 mt-1">
          <button
            disabled={!playWithBotStore.canGoToStart}
            onclick={() => playWithBotStore.handleFirstMove()}
            class="p-2 rounded-lg hover:bg-slate-800 text-slate-400 hover:text-white disabled:opacity-30 transition cursor-pointer"
            title="First Move"
          >
            ⏮
          </button>
          <button
            disabled={!playWithBotStore.canGoToStart}
            onclick={() => playWithBotStore.handlePreviousTurn()}
            class="p-2 rounded-lg hover:bg-slate-800 text-slate-400 hover:text-white disabled:opacity-30 transition cursor-pointer"
            title="Previous Move"
          >
            <ChevronLeft size={16} />
          </button>
          <span class="text-xs font-mono font-bold text-slate-400">
            {playWithBotStore.currentMoveIndex} / {playWithBotStore.maxMoveIndex}
          </span>
          <button
            disabled={!playWithBotStore.canGoToEnd}
            onclick={() => playWithBotStore.handleNextTurn()}
            class="p-2 rounded-lg hover:bg-slate-800 text-slate-400 hover:text-white disabled:opacity-30 transition cursor-pointer"
            title="Next Move"
          >
            <ChevronRight size={16} />
          </button>
          <button
            disabled={!playWithBotStore.canGoToEnd}
            onclick={() => playWithBotStore.handleLastMove()}
            class="p-2 rounded-lg hover:bg-slate-800 text-slate-400 hover:text-white disabled:opacity-30 transition cursor-pointer"
            title="Last Move"
          >
            ⏭
          </button>
        </div>

        <!-- Result banner -->
        <div class="mt-2 p-3 text-center border rounded-xl font-bold text-sm transition-all duration-300
          {playWithBotStore.gameStatus === 'victory' ? 'bg-green-950/20 text-green-400 border-green-500/30 shadow-[0_0_15px_rgba(34,197,94,0.15)]' : ''}
          {playWithBotStore.gameStatus === 'defeat' ? 'bg-rose-950/20 text-rose-400 border-rose-500/30 shadow-[0_0_15px_rgba(239,68,68,0.15)]' : ''}
          {playWithBotStore.gameStatus === 'draw' ? 'bg-slate-800/40 text-slate-300 border-slate-700/50' : ''}">
          {#if playWithBotStore.gameStatus === 'victory'}
            🏆 VICTORY!
          {:else if playWithBotStore.gameStatus === 'defeat'}
            💀 DEFEAT
          {:else}
            🤝 DRAW ({playWithBotStore.gameEndReason})
          {/if}
        </div>
      {/if}
    </div>

  </div>

</div>

<style>
  /* Custom scrollbar matching premium theme */
  .custom-scrollbar::-webkit-scrollbar {
    width: 6px;
  }
  .custom-scrollbar::-webkit-scrollbar-track {
    background: transparent;
  }
  .custom-scrollbar::-webkit-scrollbar-thumb {
    background-color: rgb(71 85 105 / 0.5);
    border-radius: 9999px;
  }
</style>
