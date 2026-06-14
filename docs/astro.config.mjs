// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import mermaid from 'astro-mermaid';

// https://astro.build/config
export default defineConfig({
	site: 'https://rabestro.github.io',
	base: '/dicechess-analytics',
	integrations: [
		mermaid(),
		starlight({
			title: 'Dice Chess Analytics',
			social: [{ icon: 'github', label: 'GitHub', href: 'https://github.com/rabestro/dicechess-analytics' }],
			sidebar: [
				{
					label: 'Overview',
					link: '/',
				},
				{
					label: 'Milestones & Roadmap',
					link: '/milestones',
				},
				{
					label: 'Architecture & Schema',
					link: '/architecture',
				},
				{
					label: 'Development & Setup',
					link: '/development',
				},
				{
					label: 'API Specification',
					link: '/api-specification',
				},
				{
					label: 'Game Ingestion',
					link: '/ingestion',
				},
			],
		}),
	],
});
