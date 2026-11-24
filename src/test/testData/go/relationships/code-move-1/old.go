package orders

func Process(o Order) error {
	if err := save(o); err != nil {
		return err
	}
	notify(o.Customer, o.ID, "processed")
	if o.Total <= 0 || len(o.Items) == 0 {
		return errInvalid
	}
	return nil
}
