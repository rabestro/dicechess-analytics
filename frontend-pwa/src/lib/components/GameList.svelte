<script lang="ts">
  import { onMount } from 'svelte';
  import { gameListStore } from '../stores/gameListStore.svelte';
  import { activeGameStore } from '../stores/activeGameStore.svelte';

  onMount(() => {
    gameListStore.fetchGames();
  });
</script>

<div class="flex flex-col gap-4">
  {#if gameListStore.loading}
    <div class="animate-pulse flex flex-col gap-3">
      {#each Array(4) as _}
        <div class="h-16 bg-slate-800/40 rounded-xl border border-slate-700/20"></div>
      {/each}
    </div>
  {:else if gameListStore.games.length === 0}
    <div class="text-slate-500 italic text-center py-12">No games played yet</div>
  {:else}
    <div class="flex flex-col gap-3">
      {#each gameListStore.games as game}
        <button
          class="w-full flex flex-col sm:flex-row sm:items-center justify-between p-4 bg-slate-900/40 hover:bg-slate-800/40 border border-slate-800/80 rounded-xl transition-all duration-200 text-left cursor-pointer active:scale-[0.99] gap-3 shadow-md hover:shadow-lg hover:border-slate-700"
          onclick={() => activeGameStore.loadGame(game.id)}
        >
          <!-- Players (White vs Black) -->
          <div class="flex items-center gap-3 min-w-0 flex-1">
            <!-- White Player -->
            <div class="flex items-center gap-2 min-w-0">
              <span class="w-6 h-6 rounded bg-slate-100 flex items-center justify-center text-[10px] font-bold text-slate-900 border border-slate-300 select-none">W</span>
              <span class="font-semibold text-slate-100 truncate text-sm">{game.white_player.username}</span>
            </div>

            <span class="text-[9px] text-slate-500 font-bold px-1.5 py-0.5 rounded bg-slate-950/60 font-mono select-none">VS</span>

            <!-- Black Player -->
            <div class="flex items-center gap-2 min-w-0">
              <span class="w-6 h-6 rounded bg-slate-800 flex items-center justify-center text-[10px] font-bold text-slate-350 border border-slate-700 select-none">B</span>
              <span class="font-semibold text-slate-100 truncate text-sm">{game.black_player.username}</span>
            </div>
          </div>

          <!-- Metadata & Status -->
          <div class="flex items-center gap-4 shrink-0 justify-between sm:justify-end">
            <!-- Turns -->
            <div class="flex items-center gap-1.5 text-xs text-slate-400 bg-slate-950/40 px-2.5 py-1 rounded-lg border border-slate-850 font-mono">
              <span class="font-bold text-slate-300">{game.total_turns}</span> turns
            </div>

            <!-- Result badge -->
            <span class="inline-flex items-center rounded-lg px-2.5 py-1 text-xs font-extrabold border shadow-sm font-mono
              {game.result === 1 ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20 shadow-emerald-950/20' :
               game.result === -1 ? 'bg-rose-500/10 text-rose-400 border-rose-500/20 shadow-rose-950/20' :
               'bg-slate-500/10 text-slate-400 border-slate-500/20 shadow-slate-950/20'}">
              {game.result === 1 ? '1-0' : game.result === -1 ? '0-1' : '½-½'}
            </span>
          </div>
        </button>
      {/each}
    </div>
  {/if}
</div>
