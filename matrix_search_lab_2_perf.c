/*
 * matrix_search_perf.c
 * Requirements:
 * - Generate N*N matrix with NO repeated numbers (1..N^2 shuffled)
 * - Input a number; search in the matrix:
 *   - Sequential search (scan matrix)
 *   - Binary search (search in sorted 1D copy; cache sorted copy so repeat searches count only search time)
 *   - Hash search (value->index map; cache hash so repeat searches count only search time)
 * - Display location if found else "Not found"
 * - Output times for the three searches separately
 *
 * Build (Linux/Mac):
 *   gcc -O2 -std=c11 matrix_search_perf.c -o matrix_search_perf
 *
 * Note: For Windows, replace clock_gettime with QueryPerformanceCounter or use C11 timespec_get.
 */

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <time.h>

typedef struct {
    int key;
    int value;   // linear index (row*N + col)
    int in_use;
} Entry;

typedef struct {
    Entry* table;
    size_t cap;
    size_t size;
} HashMap;

static uint64_t now_ns(void) {
#if defined(CLOCK_MONOTONIC)
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
#else
    // Fallback (less accurate)
    return (uint64_t)clock() * (1000000000ull / (uint64_t)CLOCKS_PER_SEC);
#endif
}

static size_t next_pow2(size_t x) {
    size_t p = 1;
    while (p < x) p <<= 1;
    return p;
}

static uint32_t hash_int(int x) {
    // simple integer hash (mix)
    uint32_t v = (uint32_t)x;
    v ^= v >> 16;
    v *= 0x7feb352d;
    v ^= v >> 15;
    v *= 0x846ca68b;
    v ^= v >> 16;
    return v;
}

static void hm_init(HashMap* hm, size_t expected) {
    hm->cap = next_pow2(expected * 2 + 1);
    hm->size = 0;
    hm->table = (Entry*)calloc(hm->cap, sizeof(Entry));
    if (!hm->table) {
        fprintf(stderr, "Out of memory\n");
        exit(1);
    }
}

static void hm_free(HashMap* hm) {
    free(hm->table);
    hm->table = NULL;
    hm->cap = hm->size = 0;
}

static void hm_put(HashMap* hm, int key, int value) {
    size_t mask = hm->cap - 1;
    size_t i = (size_t)hash_int(key) & mask;

    while (hm->table[i].in_use) {
        if (hm->table[i].key == key) {
            hm->table[i].value = value;
            return;
        }
        i = (i + 1) & mask;
    }
    hm->table[i].in_use = 1;
    hm->table[i].key = key;
    hm->table[i].value = value;
    hm->size++;
}

static int hm_get(const HashMap* hm, int key, int* out_value) {
    if (!hm->table) return 0;
    size_t mask = hm->cap - 1;
    size_t i = (size_t)hash_int(key) & mask;

    while (hm->table[i].in_use) {
        if (hm->table[i].key == key) {
            *out_value = hm->table[i].value;
            return 1;
        }
        i = (i + 1) & mask;
    }
    return 0;
}

static int cmp_int(const void* a, const void* b) {
    int x = *(const int*)a;
    int y = *(const int*)b;
    return (x > y) - (x < y);
}

typedef struct {
    int N;
    int* mat;          // N*N
    int* sorted;       // cached sorted 1D copy (values)
    HashMap pos_map;   // cached value -> linear index
    int has_sorted;
    int has_hash;
} MatrixState;

static void free_state(MatrixState* st) {
    free(st->mat);
    st->mat = NULL;

    free(st->sorted);
    st->sorted = NULL;

    if (st->has_hash) hm_free(&st->pos_map);
    st->has_hash = 0;

    st->has_sorted = 0;
    st->N = 0;
}

static void fisher_yates_shuffle(int* a, int n) {
    for (int i = n - 1; i > 0; --i) {
        int j = rand() % (i + 1);
        int tmp = a[i];
        a[i] = a[j];
        a[j] = tmp;
    }
}

static void generate_matrix(MatrixState* st, int N) {
    free_state(st);
    st->N = N;

    int total = N * N;
    st->mat = (int*)malloc(sizeof(int) * total);
    if (!st->mat) {
        fprintf(stderr, "Out of memory\n");
        exit(1);
    }

    // fill 1..N^2
    for (int i = 0; i < total; i++) st->mat[i] = i + 1;
    fisher_yates_shuffle(st->mat, total);

    st->has_sorted = 0;
    st->has_hash = 0;
}

static int sequential_search(const MatrixState* st, int target, int* out_r, int* out_c) {
    int total = st->N * st->N;
    for (int idx = 0; idx < total; idx++) {
        if (st->mat[idx] == target) {
            *out_r = idx / st->N;
            *out_c = idx % st->N;
            return 1;
        }
    }
    return 0;
}

static void ensure_sorted(MatrixState* st) {
    if (st->has_sorted) return;
    int total = st->N * st->N;
    st->sorted = (int*)malloc(sizeof(int) * total);
    if (!st->sorted) {
        fprintf(stderr, "Out of memory\n");
        exit(1);
    }
    memcpy(st->sorted, st->mat, sizeof(int) * total);
    qsort(st->sorted, total, sizeof(int), cmp_int);
    st->has_sorted = 1;
}

static void ensure_hash(MatrixState* st) {
    if (st->has_hash) return;
    int total = st->N * st->N;
    hm_init(&st->pos_map, (size_t)total);
    for (int idx = 0; idx < total; idx++) {
        hm_put(&st->pos_map, st->mat[idx], idx);
    }
    st->has_hash = 1;
}

static int binary_search_cached(MatrixState* st, int target, int* out_r, int* out_c) {
    ensure_sorted(st);

    int total = st->N * st->N;
    // manual binary search
    int lo = 0, hi = total - 1;
    while (lo <= hi) {
        int mid = lo + (hi - lo) / 2;
        int v = st->sorted[mid];
        if (v == target) {
            // value exists; get its position from hash map (build once if needed)
            ensure_hash(st);
            int idx;
            if (hm_get(&st->pos_map, target, &idx)) {
                *out_r = idx / st->N;
                *out_c = idx % st->N;
                return 1;
            }
            return 0;
        } else if (v < target) {
            lo = mid + 1;
        } else {
            hi = mid - 1;
        }
    }
    return 0;
}

static int hash_search_cached(MatrixState* st, int target, int* out_r, int* out_c) {
    ensure_hash(st);
    int idx;
    if (hm_get(&st->pos_map, target, &idx)) {
        *out_r = idx / st->N;
        *out_c = idx % st->N;
        return 1;
    }
    return 0;
}

static void print_matrix_preview(const MatrixState* st) {
    // For big N, don't print entire matrix
    int N = st->N;
    int show = (N <= 10) ? N : 10;
    printf("\nMatrix preview (%dx%d), showing top-left %dx%d:\n", N, N, show, show);
    for (int r = 0; r < show; r++) {
        for (int c = 0; c < show; c++) {
            printf("%6d ", st->mat[r * N + c]);
        }
        printf("\n");
    }
    if (N > show) printf("... (not displaying full matrix)\n");
}

int main(void) {
    srand((unsigned)time(NULL));

    MatrixState st = {0};

    printf("Matrix Data Search Performance Evaluation (C)\n");

    while (1) {
        printf("\nMenu:\n");
        printf(" 1) Generate matrix\n");
        printf(" 2) Sequential search\n");
        printf(" 3) Binary search (cached sorted)\n");
        printf(" 4) Hash search (cached map)\n");
        printf(" 5) Run all three searches (compare)\n");
        printf(" 0) Exit\n");
        printf("Select: ");

        int choice = -1;
        if (scanf("%d", &choice) != 1) break;

        if (choice == 0) break;

        if (choice == 1) {
            int N;
            printf("Enter N: ");
            if (scanf("%d", &N) != 1 || N <= 0) {
                printf("Invalid N.\n");
                continue;
            }
            // safeguard
            if ((int64_t)N * (int64_t)N > 200000000) {
                printf("N too large for this demo.\n");
                continue;
            }
            generate_matrix(&st, N);
            print_matrix_preview(&st);
            printf("Generated %dx%d matrix with unique numbers.\n", N, N);
            continue;
        }

        if (!st.mat) {
            printf("Generate a matrix first.\n");
            continue;
        }

        int target;
        printf("Enter number to search: ");
        if (scanf("%d", &target) != 1) break;

        int r = -1, c = -1, found = 0;

        if (choice == 2) {
            uint64_t t0 = now_ns();
            found = sequential_search(&st, target, &r, &c);
            uint64_t t1 = now_ns();
            printf(found ? "Result: Found at (%d, %d)\n" : "Result: Not found\n", r, c);
            printf("Sequential time: %.3f ms\n", (t1 - t0) / 1e6);
        } else if (choice == 3) {
            // measure ONLY search time if sorted already exists; otherwise includes sorting once (as required)
            uint64_t t0 = now_ns();
            found = binary_search_cached(&st, target, &r, &c);
            uint64_t t1 = now_ns();
            printf(found ? "Result: Found at (%d, %d)\n" : "Result: Not found\n", r, c);
            printf("Binary time: %.3f ms\n", (t1 - t0) / 1e6);
        } else if (choice == 4) {
            uint64_t t0 = now_ns();
            found = hash_search_cached(&st, target, &r, &c);
            uint64_t t1 = now_ns();
            printf(found ? "Result: Found at (%d, %d)\n" : "Result: Not found\n", r, c);
            printf("Hash time: %.3f ms\n", (t1 - t0) / 1e6);
        } else if (choice == 5) {
            uint64_t t0, t1;

            // Sequential
            r = c = -1;
            t0 = now_ns();
            found = sequential_search(&st, target, &r, &c);
            t1 = now_ns();
            printf("\nSequential: %s", found ? "Found" : "Not found");
            if (found) printf(" at (%d, %d)", r, c);
            printf(", time: %.3f ms\n", (t1 - t0) / 1e6);

            // Binary
            r = c = -1;
            t0 = now_ns();
            found = binary_search_cached(&st, target, &r, &c);
            t1 = now_ns();
            printf("Binary:     %s", found ? "Found" : "Not found");
            if (found) printf(" at (%d, %d)", r, c);
            printf(", time: %.3f ms\n", (t1 - t0) / 1e6);

            // Hash
            r = c = -1;
            t0 = now_ns();
            found = hash_search_cached(&st, target, &r, &c);
            t1 = now_ns();
            printf("Hash:       %s", found ? "Found" : "Not found");
            if (found) printf(" at (%d, %d)", r, c);
            printf(", time: %.3f ms\n", (t1 - t0) / 1e6);
        } else {
            printf("Unknown choice.\n");
        }
    }

    free_state(&st);
    printf("Bye.\n");
    return 0;
}