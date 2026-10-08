import csv
import datetime
import time
import psutil
import subprocess

def get_hiba_pid():
    try:
        pid = subprocess.check_output(["pgrep", "-f", "java -jar hiba.jar"]).decode().strip()
        return int(pid)
    except subprocess.CalledProcessError:
        return None

def print_usage(filename):
    hibia_pid = get_hiba_pid()
    hibia_process = psutil.Process(hibia_pid)

    cpu_count = psutil.cpu_count()  # Get the number of CPU cores

    with open(filename, "a", newline='') as file:
        csv_writer = csv.writer(file)
        # Check if it's a new file, write the headers only if it is
        if file.tell() == 0:
            csv_writer.writerow(["Date", "Time", "CPU Usage(%)", "RAM Usage(%)", "RAM Usage(MB)", "hibia CPU Usage(%)", "hibia RAM Usage(MB)"])

    while True:
        # Get system-wide CPU usage
        cpu_usage = psutil.cpu_percent()

        # Get system-wide RAM usage
        ram = psutil.virtual_memory()
        ram_usage_percent = ram.percent
        ram_usage_mb = ram.used / 1024 / 1024  # Convert to MB

        # Get hibia.jar process CPU usage
        hibia_cpu_usage = hibia_process.cpu_percent() / cpu_count  # Normalize by the number of cores

        # Get hibia.jar process RAM usage
        hibia_ram_usage_mb = hibia_process.memory_info().rss / 1024 / 1024  # Convert to MB

        # Get the current time
        now = datetime.datetime.now()
        date_str = now.strftime('%Y-%m-%d')
        time_str = now.strftime('%H:%M:%S')

        # Write the data to csv
        with open(filename, "a", newline='') as file:
            csv_writer = csv.writer(file)
            csv_writer.writerow([date_str, time_str, cpu_usage, ram_usage_percent, ram_usage_mb, hibia_cpu_usage, hibia_ram_usage_mb])

        # Also print the data to console
        print(f"{time_str}, CPU Usage(%): {cpu_usage}, RAM Usage(%): {ram_usage_percent}, RAM Usage(MB): {ram_usage_mb}, hibia CPU Usage(% per core): {hibia_cpu_usage}, hibia RAM Usage(MB): {hibia_ram_usage_mb}")

        start_time = time.perf_counter()  # get the start time

        # sleep exactly 1 second
        while True:
            time.sleep(0.01)  # sleep a very short time to not use 100% CPU
            elapsed_time = time.perf_counter() - start_time  # calculate elapsed time
            if elapsed_time >= 1:  # if one second or more has passed, break the loop
                break

if __name__ == "__main__":
    now = datetime.datetime.now()
    filename = now.strftime("Device_%Y-%m-%d_%H-%M-%S.csv")  # change the file name format
    print_usage(filename)