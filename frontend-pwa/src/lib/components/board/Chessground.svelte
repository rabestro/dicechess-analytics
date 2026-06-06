<script lang="ts">
    import { onMount, onDestroy } from 'svelte';
    import { Chessground as ChessgroundApi } from '@lichess-org/chessground';
    import type { Api } from '@lichess-org/chessground/api';
    import type { Config } from '@lichess-org/chessground/config';

    interface Props extends Config {
        api?: Api;
        class?: string;
    }

    let {
        api = $bindable(),
        class: className = '',
        ...config
    }: Props = $props();

    let boardElement: HTMLDivElement;

    let isMounted = false;

    onMount(() => {
        api = ChessgroundApi(boardElement, config);
        isMounted = true;
    });

    onDestroy(() => {
        if (api) {
            api.destroy();
        }
    });

    // Reactive update of the board configuration
    $effect(() => {
        // Skip the first update because it was already set in onMount
        if (api && isMounted) {
            api.set(config);
        }
    });
</script>

<div bind:this={boardElement} class="cg-wrap {className}"></div>

<style>
    .cg-wrap {
        position: absolute;
        top: 0;
        left: 0;
        width: 100%;
        height: 100%;
    }
</style>
