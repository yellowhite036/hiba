import csv
import datetime
import time
import psutil

def print_network_bytes_per_second(filename):
    while True:
        net_info1 = psutil.net_io_counters()
        time.sleep(1)
        net_info2 = psutil.net_io_counters()

        sent_bytes_diff = net_info2.bytes_sent - net_info1.bytes_sent
        recv_bytes_diff = net_info2.bytes_recv - net_info1.bytes_recv

        sent_mbps = sent_bytes_diff * 8 / 1000000  # Convert to Mbps
        recv_mbps = recv_bytes_diff * 8 / 1000000  # Convert to Mbps

        currentTime = datetime.datetime.now()
        date_str = currentTime.strftime("%Y-%m-%d")
        time_str = currentTime.strftime("%H:%M:%S")
        
        # Write the data to csv
        with open(filename, "a", newline='') as file:
            csv_writer = csv.writer(file)
            if file.tell() == 0:
                csv_writer.writerow(["Date", "Time", "Sent(Mbps)", "Received(Mbps)"])
            csv_writer.writerow([date_str, time_str, sent_mbps, recv_mbps])

        # Also print the data to console
        print(f"{time_str}, Sent(Mbps): {sent_mbps}, Received(Mbps): {recv_mbps}")


if __name__ == "__main__":
    now = datetime.datetime.now()
    filename = now.strftime("Network_%Y-%m-%d_%H-%M-%S.csv")
    print_network_bytes_per_second(filename)