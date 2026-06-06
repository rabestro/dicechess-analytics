<script lang="ts">
  import { onMount } from 'svelte';
  import GameList from './lib/components/GameList.svelte';
  import GameViewer from './lib/components/GameViewer.svelte';
  import BotLobby from './lib/components/BotLobby.svelte';
  import PlayBot from './lib/components/PlayBot.svelte';
  import { activeGameStore } from './lib/stores/activeGameStore.svelte';
  import { playWithBotStore } from './lib/playWithBot/playWithBotStore.svelte';

  let initialized = $state(false);
  let currentTab = $state<'games' | 'play-bot'>('games');

  onMount(() => {
    initialized = true;
  });

  // Automatically switch to play-bot tab if a game starts
  $effect(() => {
    if (playWithBotStore.gameStatus !== 'idle') {
      currentTab = 'play-bot';
    }
  });
</script>

<main class="min-h-screen bg-[#0f172a] text-white font-sans antialiased">
  {#if initialized}
    <div class="max-w-7xl mx-auto p-4 sm:p-6 lg:p-8">

      <!-- Top Navigation Header -->
      <header class="flex justify-between items-center mb-8 pb-4 border-b border-slate-800">
        <div class="flex items-center gap-3">
          <div class="w-10 h-10 rounded-xl bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center shadow-lg shadow-indigo-500/20">
            <svg class="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M14.828 14.828a4 4 0 01-5.656 0M9 10h.01M15 10h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
          </div>
          <h1 class="text-2xl font-bold bg-clip-text text-transparent bg-gradient-to-r from-white to-slate-400">
            Dice Chess
          </h1>
        </div>
        <nav class="flex gap-2">
          <button
            onclick={() => currentTab = 'games'}
            class="px-4 py-2 text-sm font-semibold rounded-lg transition-all active:scale-95 cursor-pointer {currentTab === 'games' ? 'bg-indigo-600 text-white shadow-md' : 'text-slate-400 hover:text-slate-200'}"
          >
            Games
          </button>
          <button
            onclick={() => currentTab = 'play-bot'}
            class="px-4 py-2 text-sm font-semibold rounded-lg transition-all active:scale-95 cursor-pointer {currentTab === 'play-bot' ? 'bg-indigo-600 text-white shadow-md' : 'text-slate-400 hover:text-slate-200'}"
          >
            Play Bot
          </button>
        </nav>
      </header>

      <!-- Main Content Layout -->
      {#if currentTab === 'play-bot'}
        {#if playWithBotStore.gameStatus === 'idle'}
          <div class="rounded-2xl bg-slate-800/50 border border-slate-700/50 backdrop-blur-xl p-6 min-h-[500px]">
            <BotLobby />
          </div>
        {:else}
          <div class="rounded-2xl bg-slate-800/50 border border-slate-700/50 backdrop-blur-xl p-6 min-h-[500px]">
            <PlayBot />
          </div>
        {/if}
      {:else}
        <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <div class="lg:col-span-2 rounded-2xl bg-slate-800/50 border border-slate-700/50 backdrop-blur-xl p-6 min-h-[500px]">
            {#if activeGameStore.game || activeGameStore.loading}
              <GameViewer />
            {:else}
              <h2 class="text-xl font-bold text-white mb-6">Recent Games</h2>
              <GameList />
            {/if}
          </div>
          <div class="rounded-2xl bg-slate-800/50 border border-slate-700/50 backdrop-blur-xl p-6 flex flex-col gap-4">
            <h2 class="text-lg font-semibold text-slate-205">Analytics Sidebar</h2>
            <p class="text-sm text-slate-400">Click on any historical game on the left to load the Interactive Board Viewer and review the turns, moves, and rolled dice.</p>
            <div class="mt-4 p-4 rounded-xl bg-slate-900/40 border border-slate-750 text-xs text-slate-400 leading-relaxed">
              <span class="font-bold text-slate-300 block mb-1">How to Play:</span>
              Dice Chess is a variant where you roll 3 dice at the start of your turn, then make up to 3 micro-moves corresponding to the pieces rolled. Checkmate the opponent's king to win!
            </div>
          </div>
        </div>
      {/if}

    </div>
  {/if}
</main>
