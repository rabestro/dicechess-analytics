<script lang="ts">
    import { playWithBotStore } from '../playWithBot/playWithBotStore.svelte';

    interface BotDisplayInfo {
        id: string;
        name: string;
        description: string;
        difficulty: number;
        strategy: string;
        avatar: string;
    }

    let selectedBotId = $state('greedy');
    let selectedColor = $state<'white' | 'black' | 'random'>('white');
    let selectedTimeLimit = $state<number | null>(null);

    const bots: BotDisplayInfo[] = [
        {
            id: 'random',
            name: 'Random Bot',
            description: 'Makes completely random valid moves. Perfect for absolute beginners or learning the basic rules.',
            difficulty: 1,
            strategy: 'Random Legal Choice',
            avatar: '🎲'
        },
        {
            id: 'greedy',
            name: 'Greedy Bot',
            description: 'Always tries to capture the most valuable piece available, completely ignoring the future consequences.',
            difficulty: 3,
            strategy: 'Greedy Capture Bias',
            avatar: '🤑'
        }
    ];

    function getDifficultyColorClass(diff: number) {
        if (diff <= 2) {
            return {
                border: 'border-emerald-500/20 hover:border-emerald-500/50',
                glow: 'shadow-[0_0_15px_rgba(16,185,129,0.1)] hover:shadow-[0_0_25px_rgba(16,185,129,0.25)]',
                bg: 'bg-emerald-500/5',
                badge: 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20',
                text: 'text-emerald-400'
            };
        } else {
            return {
                border: 'border-cyan-500/20 hover:border-cyan-500/50',
                glow: 'shadow-[0_0_15px_rgba(6,182,212,0.1)] hover:shadow-[0_0_25px_rgba(6,182,212,0.25)]',
                bg: 'bg-cyan-500/5',
                badge: 'bg-cyan-500/10 text-cyan-400 border-cyan-500/20',
                text: 'text-cyan-400'
            };
        }
    }

    function handleChallenge(botId: string) {
        selectedBotId = botId;
        playWithBotStore.startNewGame(selectedColor, botId, selectedTimeLimit);
    }
</script>

<div class="w-full flex flex-col gap-6 max-w-4xl mx-auto py-6">
    <!-- Header Block -->
    <div class="flex flex-col md:flex-row justify-between items-start md:items-center gap-4 bg-slate-800/40 border border-slate-700/40 rounded-2xl p-6 backdrop-blur-md">
        <div>
            <h2 class="text-2xl font-extrabold text-white flex items-center gap-2">
                <span>🤖</span> Bot Match Lobby
            </h2>
            <p class="text-sm text-slate-400 mt-1">Select an AI challenger, configure controls, and enter the arena.</p>
        </div>

        <!-- Quick Game Configuration controls -->
        <div class="flex flex-wrap items-center gap-4 w-full md:w-auto">
            <!-- Time Control -->
            <div class="flex flex-col gap-1.5 min-w-[160px] grow md:grow-0">
                <span class="text-[10px] text-slate-400 font-semibold uppercase tracking-wider">Time Control</span>
                <select
                    class="bg-slate-900/60 text-xs text-slate-200 border border-slate-700/50 rounded-xl px-3 py-1.5 font-bold focus:outline-none focus:border-indigo-500 cursor-pointer h-9 w-full"
                    bind:value={selectedTimeLimit}
                >
                    <option value={null}>Casual (No limit)</option>
                    <option value={1}>🚄 Bullet 1m</option>
                    <option value={3}>⚡ Blitz 3m</option>
                    <option value={5}>⚡ Blitz 5m</option>
                    <option value={10}>⏱️ Rapid 10m</option>
                </select>
            </div>

            <!-- Player Color selection -->
            <div class="flex flex-col gap-1.5 min-w-[140px] grow md:grow-0">
                <span class="text-[10px] text-slate-400 font-semibold uppercase tracking-wider">Your Color</span>
                <div class="flex bg-slate-900/60 rounded-xl p-1 border border-slate-700/50 shrink-0 h-9 items-center">
                    <button
                        type="button"
                        class="grow py-1 px-2.5 text-xs font-bold rounded-lg transition-all h-6.5 flex items-center justify-center {selectedColor === 'white' ? 'bg-white text-slate-900 shadow' : 'text-slate-400 hover:text-slate-250'}"
                        onclick={() => selectedColor = 'white'}
                    >
                        White
                    </button>
                    <button
                        type="button"
                        class="grow py-1 px-2.5 text-xs font-bold rounded-lg transition-all h-6.5 flex items-center justify-center {selectedColor === 'random' ? 'bg-indigo-600 text-white shadow' : 'text-slate-400 hover:text-slate-250'}"
                        onclick={() => selectedColor = 'random'}
                    >
                        🎲
                    </button>
                    <button
                        type="button"
                        class="grow py-1 px-2.5 text-xs font-bold rounded-lg transition-all h-6.5 flex items-center justify-center {selectedColor === 'black' ? 'bg-slate-700 text-white shadow' : 'text-slate-400 hover:text-slate-250'}"
                        onclick={() => selectedColor = 'black'}
                    >
                        Black
                    </button>
                </div>
            </div>
        </div>
    </div>

    <!-- Bots Grid -->
    <div class="flex flex-col gap-4">
        <h3 class="text-lg font-bold text-slate-300 flex items-center gap-2">
            <span>⚔️</span> Available Opponents
        </h3>

        <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
            {#each bots as bot}
                {@const style = getDifficultyColorClass(bot.difficulty)}
                <div
                    role="button"
                    tabindex="0"
                    class="group relative border rounded-2xl p-6 bg-slate-800/20 backdrop-blur-md transition-all duration-300 flex flex-col justify-between overflow-hidden cursor-pointer {style.border} {style.glow} {selectedBotId === bot.id ? 'ring-2 ring-indigo-500/50' : ''}"
                    onclick={() => selectedBotId = bot.id}
                    onkeydown={(e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                            selectedBotId = bot.id;
                        }
                    }}
                >
                    <div class="flex gap-4">
                        <!-- Avatar (Big Emoji) -->
                        <div class="w-20 h-20 rounded-2xl border border-slate-700 bg-slate-900/60 overflow-hidden shrink-0 shadow-inner flex items-center justify-center text-4xl select-none">
                            {bot.avatar}
                        </div>

                        <!-- Bot metadata -->
                        <div class="min-w-0 grow">
                            <h4 class="font-bold text-lg text-slate-100 group-hover:text-white truncate">{bot.name}</h4>
                            <p class="text-[10px] font-semibold text-slate-500 font-mono mt-0.5">{bot.strategy}</p>

                            <!-- Stars rating of difficulty -->
                            <div class="flex items-center gap-1 mt-2">
                                <span class="text-[10px] font-bold text-slate-400 mr-1">LVL {bot.difficulty}</span>
                                <div class="flex">
                                    {#each Array(5) as _, i}
                                        <span class="text-[10px] leading-none transition-all duration-300
                                            {i < bot.difficulty ? style.text + ' drop-shadow-[0_0_5px_currentColor]' : 'text-slate-700'}">
                                            ★
                                        </span>
                                    {/each}
                                </div>
                            </div>
                        </div>
                    </div>

                    <!-- Bot Description -->
                    <p class="text-sm text-slate-400 leading-relaxed mt-4 grow mb-6">
                        {bot.description}
                    </p>

                    <!-- Bot footer / action -->
                    <div class="flex items-center justify-between border-t border-slate-700/30 pt-4 mt-auto">
                        <span class="text-[10px] font-bold uppercase tracking-wider px-2.5 py-1 rounded-md border {style.badge}">
                            {bot.difficulty <= 1 ? 'Easy' : 'Medium'}
                        </span>

                        <button
                            type="button"
                            class="text-xs font-bold text-white bg-indigo-600 hover:bg-indigo-500 active:scale-95 transition-all shadow-md px-5 py-2.5 rounded-xl flex items-center gap-1.5"
                            onclick={(e) => {
                                e.stopPropagation();
                                handleChallenge(bot.id);
                            }}
                        >
                            <span>Challenge</span>
                            <span class="text-sm">⚔️</span>
                        </button>
                    </div>
                </div>
            {/each}
        </div>
    </div>
</div>
