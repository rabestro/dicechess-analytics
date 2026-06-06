<script lang="ts">
  import { activeGameStore } from '../stores/activeGameStore.svelte';
  import Chessground from './board/Chessground.svelte';
  import '@lichess-org/chessground/assets/chessground.base.css';
  import '@lichess-org/chessground/assets/chessground.brown.css';
  import '@lichess-org/chessground/assets/chessground.cburnett.css';
  import { ArrowLeft, ChevronLeft, ChevronRight } from 'lucide-svelte';

  const fen = $derived(activeGameStore.currentFen);
  const dice = $derived(activeGameStore.currentDice);
  const game = $derived(activeGameStore.game);

  function getCburnettPieceImage(val: string): string {
    const normalized = val.trim();
    if (!normalized) return '';
    const isWhitePiece = normalized === normalized.toUpperCase();
    const color = isWhitePiece ? 'w' : 'b';
    return `/pieces/cburnett/${color}${normalized.toUpperCase()}.svg`;
  }

  function isPieceLikeDie(value: string): boolean {
    const normalized = value.trim();
    return /^[wb]?[kqrbnpKQRBNP]$/.test(normalized);
  }
</script>

{#if activeGameStore.loading}
  <div class="animate-pulse flex flex-col items-center justify-center min-h-[400px]">
    <div class="h-8 bg-slate-700 rounded w-1/3 mb-8"></div>
    <div class="w-full max-w-md aspect-square bg-slate-800 rounded-xl"></div>
  </div>
{:else if game}
  <div class="flex flex-col gap-6 w-full max-w-2xl mx-auto">

    <!-- Header -->
    <div class="flex items-center justify-between">
      <button
        class="p-2 -ml-2 rounded-lg hover:bg-slate-700/50 text-slate-400 hover:text-white transition-colors"
        onclick={() => activeGameStore.closeGame()}
      >
        <ArrowLeft size={20} />
      </button>
      <div class="flex flex-col items-center">
        <h2 class="text-xl font-bold text-white">
          {game.white_player.username} vs {game.black_player.username}
        </h2>
        <span class="text-sm text-slate-400">
          Result: {game.result === 1 ? '1-0' : game.result === -1 ? '0-1' : '1/2-1/2'} • {game.total_turns} turns
        </span>
      </div>
      <div class="w-8"></div> <!-- Spacer for alignment -->
    </div>

    <!-- Board Container -->
    <div class="w-full max-w-[min(100%,calc(100vh-280px))] aspect-square mx-auto rounded-xl overflow-hidden shadow-2xl shadow-black/50 border border-slate-700/50 relative">
      <Chessground
        {fen}
        viewOnly={true}
        class="absolute inset-0"
      />
    </div>

    <!-- Controls -->
    <div class="flex items-center justify-between bg-slate-800/50 p-4 rounded-xl border border-slate-700/50">
      <button
        class="p-2 rounded-lg hover:bg-slate-700 transition-colors disabled:opacity-50 disabled:hover:bg-transparent"
        disabled={activeGameStore.currentTurnIndex === 0}
        onclick={() => activeGameStore.prevTurn()}
      >
        <ChevronLeft size={24} />
      </button>

      <div class="flex flex-col items-center">
        <span class="text-xs font-medium text-slate-400 uppercase tracking-wider mb-1">
          Turn {activeGameStore.currentTurnIndex + 1}
        </span>
        <div class="flex gap-2 md:gap-3">
          {#if dice}
            {#each Array.from(dice) as die}
              <div class="w-10 h-10 md:w-12 md:h-12 rounded-lg bg-gradient-to-br from-red-800 to-red-950 border-2 border-red-900/50 flex items-center justify-center shadow-[0_4px_15px_rgba(0,0,0,0.5)]">
                {#if isPieceLikeDie(die)}
                    <img
                        src={getCburnettPieceImage(die)}
                        class="w-3/4 h-3/4 object-contain filter drop-shadow-md"
                        alt={die}
                    />
                {:else}
                    <span class="text-white font-bold text-lg md:text-2xl uppercase select-none">{die}</span>
                {/if}
              </div>
            {/each}
          {:else}
            <span class="text-slate-500 italic">No dice</span>
          {/if}
        </div>
      </div>

      <button
        class="p-2 rounded-lg hover:bg-slate-700 transition-colors disabled:opacity-50 disabled:hover:bg-transparent"
        disabled={activeGameStore.currentTurnIndex === game.turns.length - 1}
        onclick={() => activeGameStore.nextTurn()}
      >
        <ChevronRight size={24} />
      </button>
    </div>

  </div>
{/if}
