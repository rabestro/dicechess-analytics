<script lang="ts">
    import { activeGameStore } from "../stores/activeGameStore.svelte";

    type DieView = {
        value: string;
        allowed?: boolean;
        used?: boolean;
    };

    let {
        horizontal = false,
        hideDice = false,
        dicesOverride = null,
        isWhiteTurnOverride = null,
        showRollButton = false,
        reserveRollHeight = false,
        onRoll = undefined,
        isAnimatingRoll = false
    } = $props<{
        horizontal?: boolean;
        hideDice?: boolean;
        dicesOverride?: DieView[] | null;
        isWhiteTurnOverride?: boolean | null;
        showRollButton?: boolean;
        reserveRollHeight?: boolean;
        onRoll?: () => void;
        isAnimatingRoll?: boolean;
    }>();

    let dices = $derived.by(() => {
        if (dicesOverride) {
            return dicesOverride.map((die: DieView) => ({
                value: die.value,
                allowed: die.allowed ?? true,
                used: die.used ?? false
            }));
        }

        // Fallback to activeGameStore dice (string format, convert to DieView)
        const activeDice = activeGameStore.currentDice;
        if (!activeDice) return [];
        return Array.from(activeDice).map(char => ({
            value: char,
            allowed: true,
            used: false
        }));
    });

    let visibleDices = $derived(hideDice ? [] : dices);

    let isWhiteTurn = $derived.by(() => {
        if (isWhiteTurnOverride !== null) {
            return isWhiteTurnOverride;
        }
        const activeDice = activeGameStore.currentDice;
        if (activeDice && activeDice.length > 0) {
            const firstDice = activeDice[0];
            return firstDice === firstDice.toUpperCase();
        }
        return true;
    });

    let imgErrors = $state<Record<number, boolean>>({});

    $effect(() => {
        if (isAnimatingRoll) {
            imgErrors = {};
        }
    });

    function isPieceLikeDie(value: string): boolean {
        const normalized = value.trim();
        return /^[wb]?[kqrbnpKQRBNP]$/.test(normalized);
    }

    function getCburnettPieceImage(val: string): string {
        const normalized = val.trim();
        if (!normalized) return '';
        const isWhitePiece = normalized === normalized.toUpperCase();
        const color = isWhitePiece ? 'w' : 'b';
        return `/pieces/cburnett/${color}${normalized.toUpperCase()}.svg`;
    }
</script>

<div class="flex {horizontal ? 'flex-row' : 'flex-col'} items-center justify-center {horizontal ? `w-full max-w-lg mx-auto py-1 ${reserveRollHeight ? 'min-h-[104px] md:min-h-[120px]' : 'min-h-[60px] md:min-h-[72px]'}` : 'h-full w-full p-3'} bg-slate-900/40 rounded-lg md:rounded-xl border border-gray-700 shadow-lg flex-shrink-0">
    {#if !horizontal}
        <h3 class="text-gray-400 font-bold mb-6 text-xs tracking-widest uppercase">Active Dice</h3>
    {/if}

    {#if visibleDices.length > 0}
        <div class="flex {horizontal ? 'flex-row gap-3 md:gap-6' : 'flex-col gap-4'} items-center">
             {#each visibleDices as d, idx}
                  <div class="relative {horizontal ? 'w-12 h-12 md:w-14 md:h-14' : 'w-16 h-16'} rounded-lg md:rounded-xl bg-gradient-to-br from-red-800 to-red-950 border-2 border-red-900/50 flex items-center justify-center shadow-[0_4px_15px_rgba(0,0,0,0.5)] transition-all duration-300
                    {d.used ? 'opacity-30 grayscale scale-95' : 'scale-100'}
                    {isAnimatingRoll ? 'animate-[spin_0.3s_linear_infinite] opacity-80' : ''}">
                    {#if isPieceLikeDie(d.value) && !imgErrors[idx]}
                        <img
                            src={getCburnettPieceImage(d.value)}
                            class="w-3/4 h-3/4 object-contain filter drop-shadow-md"
                            alt={d.value}
                            onerror={() => imgErrors[idx] = true}
                        />
                    {:else}
                        <span class="text-white font-bold text-lg md:text-2xl uppercase select-none">{d.value}</span>
                    {/if}
                </div>
            {/each}

            {#if horizontal}
                <div class="ml-4 px-3 py-1 rounded-full text-[10px] font-bold border {isWhiteTurn ? 'bg-slate-200 text-gray-800 border-white' : 'bg-slate-800 text-gray-300 border-gray-500'}">
                    {isWhiteTurn ? 'WHITE' : 'BLACK'}
                </div>
            {/if}
        </div>

        {#if !horizontal}
            <div class="mt-8 px-3 py-1 rounded-full text-[10px] font-bold border {isWhiteTurn ? 'bg-slate-200 text-gray-800 border-white' : 'bg-slate-800 text-gray-300 border-gray-500'}">
                {isWhiteTurn ? 'WHITE' : 'BLACK'} TURN
            </div>
        {/if}
    {:else}
        {#if showRollButton}
            <div class="flex items-center justify-center {horizontal ? 'w-full py-2' : 'h-full'}">
                <button
                    class="relative flex flex-col items-center justify-center w-20 h-20 md:w-24 md:h-24 rounded-full bg-gradient-to-br from-red-800 to-red-950 border-[3px] border-red-500 shadow-[0_0_20px_rgba(239,68,68,0.5)] transition-transform hover:scale-105 active:scale-95 cursor-pointer"
                    onclick={onRoll}
                    disabled={isAnimatingRoll}
                >
                    <div class="absolute inset-0 rounded-full ring-4 ring-red-500/30 animate-pulse"></div>
                    <span class="text-3xl mb-1">🎲</span>
                    <span class="text-white font-bold text-sm tracking-widest text-shadow">ROLL</span>
                </button>
            </div>
        {:else}
            <div class="text-gray-500 text-xs md:text-sm italic text-center {horizontal ? 'py-1' : ''}">No dice rolled</div>
        {/if}
    {/if}
</div>
