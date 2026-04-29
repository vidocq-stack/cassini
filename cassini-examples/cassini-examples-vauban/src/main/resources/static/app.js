// Cassini + Vauban — Todo UI client

const API = '/api';

const $list = document.getElementById('todo-list');
const $form = document.getElementById('add-form');
const $input = document.getElementById('add-input');
const $greeting = document.getElementById('greeting');

// --- Greeting (démontre une autre ressource Cassini) ---

async function loadGreeting() {
    try {
        const res = await fetch(`${API}/greetings/Vauban`, {
            headers: { Accept: 'text/plain' },
        });
        $greeting.textContent = await res.text();
    } catch (e) {
        $greeting.textContent = `(greeting unavailable: ${e.message})`;
    }
}

// --- Todos ---

function render(todos) {
    $list.innerHTML = '';
    if (!todos.length) {
        $list.innerHTML = '<li class="empty">Aucune tâche pour le moment.</li>';
        return;
    }
    for (const t of todos) {
        const li = document.createElement('li');
        if (t.done) li.classList.add('done');
        li.innerHTML = `
            <input type="checkbox" ${t.done ? 'checked' : ''}>
            <span class="title"></span>
            <button class="delete" aria-label="Supprimer">✕</button>
        `;
        li.querySelector('.title').textContent = t.title;
        li.querySelector('input').addEventListener('change', e =>
            toggle(t, e.target.checked));
        li.querySelector('.delete').addEventListener('click', () => remove(t));
        $list.appendChild(li);
    }
}

async function loadTodos() {
    const res = await fetch(`${API}/todos`, { headers: { Accept: 'application/json' } });
    if (!res.ok) {
        $list.innerHTML = `<li class="empty">Erreur ${res.status}</li>`;
        return;
    }
    const todos = await res.json();
    render(todos);
}

async function add(title) {
    const res = await fetch(`${API}/todos`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            Accept: 'application/json',
        },
        body: JSON.stringify({ id: 0, title, done: false }),
    });
    if (!res.ok) throw new Error(`POST failed (${res.status})`);
    await loadTodos();
}

async function toggle(todo, done) {
    const res = await fetch(`${API}/todos/${todo.id}`, {
        method: 'PUT',
        headers: {
            'Content-Type': 'application/json',
            Accept: 'application/json',
        },
        body: JSON.stringify({ id: todo.id, title: todo.title, done }),
    });
    if (!res.ok) console.error(`PUT failed (${res.status})`);
    await loadTodos();
}

async function remove(todo) {
    const res = await fetch(`${API}/todos/${todo.id}`, { method: 'DELETE' });
    if (!res.ok) console.error(`DELETE failed (${res.status})`);
    await loadTodos();
}

$form.addEventListener('submit', async e => {
    e.preventDefault();
    const title = $input.value.trim();
    if (!title) return;
    $input.value = '';
    try {
        await add(title);
    } catch (err) {
        alert(err.message);
    }
});

// --- Init ---

loadGreeting();
loadTodos();
