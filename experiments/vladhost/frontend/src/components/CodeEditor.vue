<script setup lang="ts">
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { bracketMatching, indentOnInput, syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language'
import { Compartment, EditorState, type Extension } from '@codemirror/state'
import { oneDark } from '@codemirror/theme-one-dark'
import { drawSelection, EditorView, highlightActiveLine, keymap, lineNumbers } from '@codemirror/view'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { langFor, languageExtension } from '@/lib/language'
import { theme, type Theme } from '@/lib/theme'

const props = defineProps<{ modelValue: string; filename: string }>()
const emit = defineEmits<{ 'update:modelValue': [string]; save: [] }>()

const host = ref<HTMLDivElement>()
let view: EditorView | undefined

// Оформление редактора следует за темой панели; меняется на лету, без пересоздания (курсор и история правок остаются).
const themeSlot = new Compartment()
function editorTheme(t: Theme): Extension {
  if (t === 'light') {
    return EditorView.theme(
      {
        '&': { backgroundColor: '#ffffff', borderRadius: '14px' },
        '.cm-gutters': { backgroundColor: 'transparent', border: 'none', color: 'rgba(21,24,51,.4)' },
        '.cm-activeLine': { backgroundColor: 'rgba(124,58,237,.06)' },
        '.cm-activeLineGutter': { backgroundColor: 'transparent', color: '#6d28d9' },
        '.cm-cursor': { borderLeftColor: '#db2777' },
        '&.cm-focused .cm-selectionBackground, .cm-selectionBackground': { backgroundColor: 'rgba(124,58,237,.18) !important' },
      },
      { dark: false },
    )
  }
  // Свой фон и рамка под стеклянный интерфейс: цвета подсветки остаются от oneDark.
  return [
    oneDark,
    EditorView.theme(
      {
        '&': { backgroundColor: 'rgba(7, 9, 20, 0.55)', borderRadius: '14px' },
        '.cm-gutters': { backgroundColor: 'transparent', border: 'none', color: 'rgba(241,242,255,.35)' },
        '.cm-activeLine': { backgroundColor: 'rgba(167,139,250,.09)' },
        '.cm-activeLineGutter': { backgroundColor: 'transparent', color: '#c4b5fd' },
        '.cm-cursor': { borderLeftColor: '#f472b6' },
        '&.cm-focused .cm-selectionBackground, .cm-selectionBackground': { backgroundColor: 'rgba(139,92,246,.4) !important' },
      },
      { dark: true },
    ),
  ]
}
watch(theme, (t) => view?.dispatch({ effects: themeSlot.reconfigure(editorTheme(t)) }))

function build(doc: string): EditorState {
  return EditorState.create({
    doc,
    extensions: [
      lineNumbers(),
      history(),
      drawSelection(),
      indentOnInput(),
      bracketMatching(),
      highlightActiveLine(),
      syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
      themeSlot.of(editorTheme(theme.value)),
      EditorView.lineWrapping,
      languageExtension(langFor(props.filename)),
      keymap.of([
        { key: 'Mod-s', preventDefault: true, run: () => (emit('save'), true) },
        indentWithTab,
        ...defaultKeymap,
        ...historyKeymap,
      ]),
      EditorView.updateListener.of((u) => {
        if (u.docChanged) emit('update:modelValue', u.state.doc.toString())
      }),
    ],
  })
}

onMounted(() => {
  view = new EditorView({ state: build(props.modelValue), parent: host.value })
})

// Внешнее изменение (открыли другой файл, откат) пересоздаёт состояние; собственные правки — нет.
watch(
  () => [props.filename, props.modelValue] as const,
  ([name, value], [oldName]) => {
    if (!view) return
    if (name !== oldName || value !== view.state.doc.toString()) view.setState(build(value))
  },
)

onBeforeUnmount(() => view?.destroy())
</script>

<template>
  <div ref="host" class="editor" />
</template>

<style scoped>
.editor { border: 1px solid var(--border); border-radius: 14px; overflow: hidden; }
.editor :deep(.cm-editor) { height: 60vh; }
.editor :deep(.cm-scroller) { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; }
</style>
