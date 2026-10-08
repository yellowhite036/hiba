import numpy as np
import time
import sys

def main(clock, core):
    a = np.random.rand(1000, 1000)
    b = np.random.rand(1000, 1000)

    start_time = time.time()
    num_steps = 1
    for i in range(num_steps):
        c = np.dot(a, b)
    end_time = time.time()

    elapsed_time = end_time - start_time
    clock = (float(clock) * 1000 ** 3)

    ops = clock / elapsed_time             # CPU頻率 / 處理時間 -> 每秒可執行次數
    tops = ops / 1e12 * int(core)          # ops / 10^12 * 核心數 -> 多個核心每秒可運算多少兆個浮點數運算

    # print(f"Elapsed time: {elapsed_time} s")
    # print(f"TOPS: {tops} TOPS")
    print(tops)

if __name__ == "__main__":
    # argv[1]: cpu clock
    # argv[2]: number of cores
    main(sys.argv[1], sys.argv[2])