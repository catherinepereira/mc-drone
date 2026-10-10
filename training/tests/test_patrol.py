import math

from drone_model.scripted.patrol import sweep


def test_sweep_visits_every_cell_row_by_row_from_the_nearest_corner():
    cells = [[x, z] for x in (4.0, 12.0, 20.0) for z in (4.0, 12.0)]
    order = sweep(cells, (21.0, 3.0))
    assert sorted(order) == sorted(tuple(c) for c in cells)
    assert order[0] == (20.0, 4.0)
    # each step goes to a neighboring cell, never across the region
    assert all(math.dist(a, b) <= 8.0 for a, b in zip(order, order[1:]))
